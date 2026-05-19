package dev.datrollout.argus.kubernetes.embabel;

import com.embabel.agent.api.annotation.LlmTool;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.discovery.v1.Endpoint;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointConditions;
import io.fabric8.kubernetes.api.model.discovery.v1.EndpointSlice;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPath;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressRuleValue;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBackend;
import io.fabric8.kubernetes.api.model.networking.v1.IngressClass;
import io.fabric8.kubernetes.api.model.networking.v1.IngressLoadBalancerIngress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressLoadBalancerStatus;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule;
import io.fabric8.kubernetes.api.model.networking.v1.IngressServiceBackend;
import io.fabric8.kubernetes.api.model.networking.v1.IngressSpec;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLS;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyEgressRule;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyIngressRule;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeer;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPort;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicySpec;
import io.fabric8.kubernetes.api.model.networking.v1.ServiceBackendPort;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LLM-callable tools for diagnosing Kubernetes networking problems.
 *
 * <p>Each tool returns a human-readable text block with three sections:
 * <ul>
 *     <li>Evidence — the raw cluster state relevant to the question</li>
 *     <li>Pattern summary — counts and correlations across resources</li>
 *     <li>SUSPICIONS — likely root causes the LLM should investigate next</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class NetworkingDebuggingTools {

    private static final int MAX_EVENTS = 10;
    private static final int MAX_LIST_PREVIEW = 20;
    private static final Set<String> KNOWN_CNI_LABELS = Set.of("k8s-app", "app.kubernetes.io/name", "name", "app");

    private final KubernetesClient kubernetesClient;

    // ──────────────────────────────────────────────────────────────────────────
    // Discovery / lookup — call BEFORE the inspect* tools when names are unknown
    // ──────────────────────────────────────────────────────────────────────────

    @LlmTool(name = "listNamespaces", description = """
                    Use this tool FIRST whenever you don't know which namespace a workload lives in
                    (e.g. the operator says "the sonarqube db" without specifying a namespace).
                    Returns every namespace in the cluster with its phase. Cheap; call this before
                    guessing namespace names.
                    """)
    public String listNamespaces() {
        List<Namespace> namespaces = kubernetesClient.namespaces().list().getItems();
        StringBuilder sb = new StringBuilder();
        sb.append("=== NAMESPACES (").append(namespaces.size()).append(") ===\n\n");
        sb.append(String.format("%-40s  %-10s%n", "NAME", "PHASE"));
        sb.append("-".repeat(55)).append("\n");
        namespaces.stream()
                .sorted(Comparator.comparing(n -> n.getMetadata().getName()))
                .forEach(n -> sb.append(String.format(
                        "%-40s  %-10s%n",
                        n.getMetadata().getName(),
                        Optional.ofNullable(n.getStatus())
                                .map(NamespaceStatus::getPhase)
                                .orElse("?"))));
        return sb.toString();
    }

    @LlmTool(name = "listServices", description = """
                    Use this tool BEFORE inspectServiceConnectivity / inspectEndpoints whenever you
                    are unsure of the exact Service name. Pass a namespace or empty string for
                    cluster-wide. Returns one row per Service with name, type, clusterIP, ports,
                    selector, and ready-endpoint count. Use this to discover correct names instead
                    of guessing.
                    """)
    public String listServices(String namespaceOrEmpty) {
        boolean hasNs = namespaceOrEmpty != null && !namespaceOrEmpty.isBlank();
        List<Service> services = hasNs
                ? kubernetesClient
                        .services()
                        .inNamespace(namespaceOrEmpty)
                        .list()
                        .getItems()
                : kubernetesClient.services().inAnyNamespace().list().getItems();

        StringBuilder sb = new StringBuilder();
        sb.append("=== SERVICES")
                .append(hasNs ? " (" + namespaceOrEmpty + ")" : " (all namespaces)")
                .append(" ===\n\n");
        if (services.isEmpty()) {
            sb.append("  (none)\n");
            return sb.toString();
        }
        appendServiceTable(sb, services);
        return sb.toString();
    }

    @LlmTool(name = "findServices", description = """
                    Use this tool when you have only a partial / fuzzy name (e.g. "postgres",
                    "redis", "auth") and need to find every Service across the cluster whose name
                    contains it (case-insensitive). Returns the same columns as listServices.
                    Always prefer this over guessing service names when an inspect* tool returns
                    'not found'.
                    """)
    public String findServices(String searchTerm) {
        if (searchTerm == null || searchTerm.isBlank()) {
            return "ERROR: searchTerm must not be empty. Pass a substring of the service name (e.g. 'postgres').";
        }
        String needle = searchTerm.toLowerCase();
        List<Service> matches = kubernetesClient.services().inAnyNamespace().list().getItems().stream()
                .filter(s -> s.getMetadata().getName().toLowerCase().contains(needle))
                .sorted(Comparator.comparing((Service s) -> s.getMetadata().getNamespace())
                        .thenComparing(s -> s.getMetadata().getName()))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("=== SERVICE SEARCH: '")
                .append(searchTerm)
                .append("' — ")
                .append(matches.size())
                .append(" match(es) ===\n\n");
        if (matches.isEmpty()) {
            sb.append("  No services contain '").append(searchTerm).append("'.\n");
            sb.append("  Try listServices(\"\") to dump everything, or relax the search term.\n");
            return sb.toString();
        }
        appendServiceTable(sb, matches);
        return sb.toString();
    }

    @LlmTool(name = "findPods", description = """
                    Use this tool when you have only a partial / fuzzy pod name (e.g. "nginx",
                    "postgres-0", "controller") and need to find every Pod across the cluster whose
                    name contains it (case-insensitive). Returns namespace, pod, phase, ready,
                    restarts, podIP, node. Always prefer this over guessing pod names when an
                    inspect* tool returns 'not found'.
                    """)
    public String findPods(String searchTerm) {
        if (searchTerm == null || searchTerm.isBlank()) {
            return "ERROR: searchTerm must not be empty. Pass a substring of the pod name (e.g. 'nginx').";
        }
        String needle = searchTerm.toLowerCase();
        List<Pod> matches = kubernetesClient.pods().inAnyNamespace().list().getItems().stream()
                .filter(p -> p.getMetadata().getName().toLowerCase().contains(needle))
                .sorted(Comparator.comparing((Pod p) -> p.getMetadata().getNamespace())
                        .thenComparing(p -> p.getMetadata().getName()))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("=== POD SEARCH: '")
                .append(searchTerm)
                .append("' — ")
                .append(matches.size())
                .append(" match(es) ===\n\n");
        if (matches.isEmpty()) {
            sb.append("  No pods contain '").append(searchTerm).append("'.\n");
            return sb.toString();
        }
        sb.append(String.format(
                "%-25s  %-55s  %-10s  %-5s  %-8s  %-15s  %s%n",
                "NAMESPACE", "POD", "PHASE", "READY", "RESTARTS", "POD-IP", "NODE"));
        sb.append("-".repeat(140)).append("\n");
        for (Pod p : matches) {
            String phase =
                    Optional.ofNullable(p.getStatus()).map(PodStatus::getPhase).orElse("?");
            int restarts = Optional.ofNullable(p.getStatus())
                    .map(PodStatus::getContainerStatuses)
                    .orElse(Collections.emptyList())
                    .stream()
                    .mapToInt(ContainerStatus::getRestartCount)
                    .sum();
            String podIp =
                    Optional.ofNullable(p.getStatus()).map(PodStatus::getPodIP).orElse("<none>");
            String node =
                    Optional.ofNullable(p.getSpec()).map(PodSpec::getNodeName).orElse("<none>");
            sb.append(String.format(
                    "%-25s  %-55s  %-10s  %-5s  %-8d  %-15s  %s%n",
                    truncate(p.getMetadata().getNamespace(), 25),
                    truncate(p.getMetadata().getName(), 55),
                    phase,
                    isPodReady(p),
                    restarts,
                    podIp,
                    truncate(node, 30)));
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Service connectivity
    // ──────────────────────────────────────────────────────────────────────────

    @LlmTool(name = "inspectServiceConnectivity", description = """
                    Use this tool when a Service is unreachable, returns "connection refused",
                    "no route to host", times out, or when ClusterIP/NodePort/LoadBalancer behaves
                    unexpectedly. Requires EXACT namespace and Service name — if you don't have
                    them, call findServices or listServices first instead of guessing. Returns the
                    Service spec (type, ports, selector, IPs, externalTrafficPolicy,
                    sessionAffinity), live LoadBalancer status, an Endpoint summary (ready/not-ready
                    addresses), a list of pods matched by the selector with their phase + readiness,
                    and a SUSPICIONS block calling out empty endpoints, selector mismatch, wrong
                    targetPort, pending LoadBalancer IPs, or stale endpoints.
                    """)
    public String inspectServiceConnectivity(String namespace, String serviceName) {
        Service svc = kubernetesClient
                .services()
                .inNamespace(namespace)
                .withName(serviceName)
                .get();
        if (svc == null) {
            return serviceNotFoundHint(namespace, serviceName);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== SERVICE CONNECTIVITY: ")
                .append(namespace)
                .append("/")
                .append(serviceName)
                .append(" ===\n\n");

        appendServiceEvidence(sb, svc);

        Endpoints endpoints = kubernetesClient
                .endpoints()
                .inNamespace(namespace)
                .withName(serviceName)
                .get();
        appendEndpointsEvidence(sb, endpoints);

        List<EndpointSlice> slices = listEndpointSlicesForService(namespace, serviceName);
        appendEndpointSlicesEvidence(sb, slices);

        Map<String, String> selector =
                Optional.ofNullable(svc.getSpec()).map(ServiceSpec::getSelector).orElse(Collections.emptyMap());

        List<Pod> matchedPods = selector.isEmpty()
                ? Collections.emptyList()
                : kubernetesClient
                        .pods()
                        .inNamespace(namespace)
                        .withLabels(selector)
                        .list()
                        .getItems();
        appendMatchedPodsEvidence(sb, selector, matchedPods);

        List<Event> events = fetchObjectEvents(namespace, serviceName);
        appendEvents(sb, events, "Service Events");

        appendServiceSuspicions(sb, svc, endpoints, slices, matchedPods);
        return sb.toString();
    }

    @LlmTool(name = "inspectEndpoints", description = """
                    Use this tool when you need a deep look at which backend IPs a Service is
                    actually routing to, including not-ready and terminating addresses. Returns
                    both legacy Endpoints and modern EndpointSlices with per-address node, target
                    pod, ready/serving/terminating conditions, and exposed ports. Use after
                    inspectServiceConnectivity to confirm whether a missing endpoint is due to
                    failing readiness probes, terminating pods, or selector drift.
                    """)
    public String inspectEndpoints(String namespace, String serviceName) {
        Service svc = kubernetesClient
                .services()
                .inNamespace(namespace)
                .withName(serviceName)
                .get();
        if (svc == null) {
            return serviceNotFoundHint(namespace, serviceName);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== ENDPOINTS DEEP DIVE: ")
                .append(namespace)
                .append("/")
                .append(serviceName)
                .append(" ===\n\n");

        Endpoints endpoints = kubernetesClient
                .endpoints()
                .inNamespace(namespace)
                .withName(serviceName)
                .get();
        if (endpoints == null) {
            sb.append("[Endpoints] none — endpoint controller did not create an Endpoints object\n");
            sb.append("  (this happens when the Service has no selector or the selector matches no pods)\n\n");
        } else {
            sb.append("[Endpoints] ").append(endpoints.getMetadata().getName()).append("\n");
            List<EndpointSubset> subsets =
                    Optional.ofNullable(endpoints.getSubsets()).orElse(Collections.emptyList());
            if (subsets.isEmpty()) {
                sb.append("  subsets: (empty)  ⚠ NO BACKEND ADDRESSES\n");
            } else {
                for (EndpointSubset subset : subsets) {
                    sb.append("  subset:\n");
                    appendEndpointAddresses(sb, "    ready    ", subset.getAddresses());
                    appendEndpointAddresses(sb, "    notReady ", subset.getNotReadyAddresses());
                    List<EndpointPort> ports =
                            Optional.ofNullable(subset.getPorts()).orElse(Collections.emptyList());
                    if (!ports.isEmpty()) {
                        sb.append("    ports:\n");
                        for (EndpointPort p : ports) {
                            sb.append("      - name=")
                                    .append(safe(p.getName()))
                                    .append(" port=")
                                    .append(p.getPort())
                                    .append(" protocol=")
                                    .append(safe(p.getProtocol()))
                                    .append("\n");
                        }
                    }
                }
            }
            sb.append("\n");
        }

        List<EndpointSlice> slices = listEndpointSlicesForService(namespace, serviceName);
        if (slices.isEmpty()) {
            sb.append("[EndpointSlices] none\n");
        } else {
            sb.append("[EndpointSlices] count=").append(slices.size()).append("\n");
            for (EndpointSlice slice : slices) {
                sb.append("  ")
                        .append(slice.getMetadata().getName())
                        .append("  addressType=")
                        .append(safe(slice.getAddressType()))
                        .append("\n");
                List<Endpoint> eps = Optional.ofNullable(slice.getEndpoints()).orElse(Collections.emptyList());
                for (Endpoint ep : eps) {
                    EndpointConditions cond = ep.getConditions();
                    String addrs = Optional.ofNullable(ep.getAddresses())
                            .map(a -> String.join(",", a))
                            .orElse("");
                    String target = Optional.ofNullable(ep.getTargetRef())
                            .map(r -> r.getKind() + "/" + r.getName())
                            .orElse("-");
                    sb.append("    addr=")
                            .append(addrs)
                            .append("  node=")
                            .append(safe(ep.getNodeName()))
                            .append("  target=")
                            .append(target)
                            .append("  ready=")
                            .append(boolStr(cond == null ? null : cond.getReady()))
                            .append("  serving=")
                            .append(boolStr(cond == null ? null : cond.getServing()))
                            .append("  terminating=")
                            .append(boolStr(cond == null ? null : cond.getTerminating()))
                            .append("\n");
                }
                List<io.fabric8.kubernetes.api.model.discovery.v1.EndpointPort> sliceports =
                        Optional.ofNullable(slice.getPorts()).orElse(Collections.emptyList());
                if (!sliceports.isEmpty()) {
                    sb.append("    ports: ");
                    sb.append(sliceports.stream()
                            .map(p -> safe(p.getName()) + ":" + p.getPort() + "/" + safe(p.getProtocol()))
                            .collect(Collectors.joining(", ")));
                    sb.append("\n");
                }
            }
        }

        sb.append("\n[SUSPICIONS]\n");
        boolean any = false;
        long readyCount = countReadyEndpoints(endpoints);
        long notReadyCount = countNotReadyEndpoints(endpoints);
        if (readyCount == 0 && notReadyCount == 0) {
            sb.append("  - No addresses at all. Selector matches no pods OR the controller hasn't\n");
            sb.append("    created the Endpoints object yet. Verify selector and pod labels.\n");
            any = true;
        }
        if (readyCount == 0 && notReadyCount > 0) {
            sb.append("  - All ").append(notReadyCount).append(" addresses are NotReady — pods exist but\n");
            sb.append("    fail their readinessProbe. Check probe path/port and application startup.\n");
            any = true;
        }
        long terminatingSliceEndpoints = slices.stream()
                .flatMap(s -> Optional.ofNullable(s.getEndpoints()).orElse(Collections.emptyList()).stream())
                .filter(e -> e.getConditions() != null
                        && Boolean.TRUE.equals(e.getConditions().getTerminating()))
                .count();
        if (terminatingSliceEndpoints > 0) {
            sb.append("  - ").append(terminatingSliceEndpoints).append(" endpoint(s) are Terminating.\n");
            sb.append("    Connections may briefly fail during pod shutdown — check terminationGracePeriodSeconds\n");
            sb.append("    and ensure your app handles SIGTERM cleanly.\n");
            any = true;
        }
        if (!any) {
            sb.append("  - Endpoints look healthy at the API level. If traffic still fails, check\n");
            sb.append("    NetworkPolicies, kube-proxy/CNI state, or container port/targetPort mismatch.\n");
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Ingress
    // ──────────────────────────────────────────────────────────────────────────

    @LlmTool(name = "inspectIngressRouting", description = """
                    Use this tool when an Ingress returns 404, "default backend", "no route", a TLS
                    error, or shows a pending external address. Returns ingressClassName, ALL
                    annotations on the Ingress (essential for Traefik/ingress-nginx behaviour —
                    SSL termination, redirects, backend protocol, rewrites, cert-manager wiring
                    are all configured via annotations), all rules (host/path/pathType/backend),
                    TLS sections with secret existence, LoadBalancer status, backend Service
                    existence + endpoint counts, and a SUSPICIONS block for missing backends,
                    missing TLS secrets, wrong path types, or unbound LB. The SUSPICIONS block
                    is annotation-aware: it recognises Traefik annotation-driven TLS and
                    cert-manager-managed secrets, so it does not falsely flag those as missing.
                    """)
    public String inspectIngressRouting(String namespace, String ingressName) {
        Ingress ing = kubernetesClient
                .network()
                .v1()
                .ingresses()
                .inNamespace(namespace)
                .withName(ingressName)
                .get();
        if (ing == null) {
            return "ERROR: ingress " + namespace + "/" + ingressName + " not found.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== INGRESS ROUTING: ")
                .append(namespace)
                .append("/")
                .append(ingressName)
                .append(" ===\n\n");

        IngressSpec spec = ing.getSpec();
        String className =
                Optional.ofNullable(spec).map(IngressSpec::getIngressClassName).orElse(null);
        sb.append("  ingressClassName: ").append(safe(className)).append("\n");
        if (className == null) {
            sb.append("    (no explicit class — the cluster default IngressClass will handle this,\n");
            sb.append("     or it will be ignored if no default exists)\n");
        }

        Map<String, String> annotations =
                Optional.ofNullable(ing.getMetadata().getAnnotations()).orElse(Collections.emptyMap());
        if (annotations.isEmpty()) {
            sb.append("  annotations: (none)\n");
        } else {
            sb.append("  annotations (").append(annotations.size()).append("):\n");
            annotations.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> sb.append("    ")
                    .append(e.getKey())
                    .append(": ")
                    .append(e.getValue())
                    .append("\n"));
        }

        IngressBackend def =
                Optional.ofNullable(spec).map(IngressSpec::getDefaultBackend).orElse(null);
        if (def != null) {
            sb.append("  defaultBackend: ").append(formatIngressBackend(def)).append("\n");
        }

        // TLS
        List<IngressTLS> tls =
                Optional.ofNullable(spec).map(IngressSpec::getTls).orElse(Collections.emptyList());
        if (tls.isEmpty()) {
            sb.append("  tls: (none — HTTP only)\n");
        } else {
            sb.append("  tls:\n");
            for (IngressTLS t : tls) {
                String secret = safe(t.getSecretName());
                String hosts = Optional.ofNullable(t.getHosts())
                        .map(h -> String.join(",", h))
                        .orElse("");
                boolean secretExists = secretExists(namespace, t.getSecretName());
                sb.append("    secret=")
                        .append(secret)
                        .append("  hosts=[")
                        .append(hosts)
                        .append("]")
                        .append("  exists=")
                        .append(secretExists);
                if (!secretExists && t.getSecretName() != null) sb.append("  ⚠ TLS SECRET MISSING");
                sb.append("\n");
            }
        }

        // Rules
        List<IngressRule> rules =
                Optional.ofNullable(spec).map(IngressSpec::getRules).orElse(Collections.emptyList());
        if (rules.isEmpty()) {
            sb.append("  rules: (none)\n");
        } else {
            sb.append("  rules:\n");
            for (IngressRule r : rules) {
                sb.append("    host=").append(safe(r.getHost())).append("\n");
                HTTPIngressRuleValue http = r.getHttp();
                List<HTTPIngressPath> paths = http == null
                        ? Collections.emptyList()
                        : Optional.ofNullable(http.getPaths()).orElse(Collections.emptyList());
                for (HTTPIngressPath p : paths) {
                    sb.append("      path=")
                            .append(safe(p.getPath()))
                            .append("  pathType=")
                            .append(safe(p.getPathType()))
                            .append("  -> ")
                            .append(formatIngressBackend(p.getBackend()))
                            .append("\n");
                }
            }
        }

        // LoadBalancer status
        IngressLoadBalancerStatus lb = Optional.ofNullable(ing.getStatus())
                .map(s -> s.getLoadBalancer())
                .orElse(null);
        List<IngressLoadBalancerIngress> lbIngress = lb == null
                ? Collections.emptyList()
                : Optional.ofNullable(lb.getIngress()).orElse(Collections.emptyList());
        if (lbIngress.isEmpty()) {
            sb.append("  status.loadBalancer.ingress: (empty)  ⚠ NO EXTERNAL ADDRESS ASSIGNED YET\n");
        } else {
            sb.append("  status.loadBalancer.ingress:\n");
            for (IngressLoadBalancerIngress li : lbIngress) {
                sb.append("    ip=")
                        .append(safe(li.getIp()))
                        .append("  hostname=")
                        .append(safe(li.getHostname()))
                        .append("\n");
            }
        }

        // Backend health
        sb.append("\n[Backend health]\n");
        Set<String> seenBackends = new HashSet<>();
        List<IngressBackend> allBackends = new ArrayList<>();
        if (def != null) allBackends.add(def);
        for (IngressRule r : rules) {
            HTTPIngressRuleValue http = r.getHttp();
            if (http == null) continue;
            for (HTTPIngressPath p : Optional.ofNullable(http.getPaths()).orElse(Collections.emptyList())) {
                allBackends.add(p.getBackend());
            }
        }
        boolean anyMissing = false;
        for (IngressBackend b : allBackends) {
            if (b == null || b.getService() == null) continue;
            IngressServiceBackend sb2 = b.getService();
            String key = sb2.getName() + ":"
                    + (sb2.getPort() == null
                            ? "-"
                            : (sb2.getPort().getNumber() != null
                                    ? sb2.getPort().getNumber().toString()
                                    : safe(sb2.getPort().getName())));
            if (!seenBackends.add(key)) continue;
            Service backendSvc = kubernetesClient
                    .services()
                    .inNamespace(namespace)
                    .withName(sb2.getName())
                    .get();
            Endpoints backendEps = backendSvc == null
                    ? null
                    : kubernetesClient
                            .endpoints()
                            .inNamespace(namespace)
                            .withName(sb2.getName())
                            .get();
            long ready = countReadyEndpoints(backendEps);
            sb.append("  service=")
                    .append(sb2.getName())
                    .append(" port=")
                    .append(formatServiceBackendPort(sb2.getPort()))
                    .append("  exists=")
                    .append(backendSvc != null)
                    .append("  readyEndpoints=")
                    .append(ready);
            if (backendSvc == null) {
                sb.append("  ⚠ BACKEND SERVICE NOT FOUND");
                anyMissing = true;
            } else if (ready == 0) {
                sb.append("  ⚠ ZERO READY ENDPOINTS");
                anyMissing = true;
            } else if (!servicePortMatches(backendSvc, sb2.getPort())) {
                sb.append("  ⚠ PORT NOT DEFINED ON SERVICE");
                anyMissing = true;
            }
            sb.append("\n");
        }

        // Ingress controller events
        List<Event> events = fetchObjectEvents(namespace, ingressName);
        appendEvents(sb, events, "Ingress Events");

        sb.append("\n[SUSPICIONS]\n");
        if (className == null && !hasDefaultIngressClass()) {
            sb.append("  - No ingressClassName set and no default IngressClass exists.\n");
            sb.append("    No controller will pick up this Ingress.\n");
        }
        if (className != null && !ingressClassExists(className)) {
            sb.append("  - ingressClassName '").append(className).append("' does not exist.\n");
            sb.append("    Install the matching controller or fix the class name.\n");
        }
        if (lbIngress.isEmpty() && !rules.isEmpty()) {
            sb.append("  - status.loadBalancer is empty. The ingress controller has not assigned an\n");
            sb.append("    external address. Check controller pods and service-of-type-LoadBalancer.\n");
        }
        boolean certManagerManaged = annotations.containsKey("cert-manager.io/cluster-issuer")
                || annotations.containsKey("cert-manager.io/issuer");
        for (IngressTLS t : tls) {
            if (t.getSecretName() != null && !secretExists(namespace, t.getSecretName())) {
                if (certManagerManaged) {
                    sb.append("  - TLS secret '")
                            .append(t.getSecretName())
                            .append("' is missing, but cert-manager annotations are present\n")
                            .append("    (")
                            .append(annotations.getOrDefault(
                                    "cert-manager.io/cluster-issuer",
                                    annotations.getOrDefault("cert-manager.io/issuer", "")))
                            .append("). cert-manager will create the secret; verify the Certificate\n")
                            .append("    resource and its issuer status if the secret never appears.\n");
                } else {
                    sb.append("  - TLS secret '")
                            .append(t.getSecretName())
                            .append("' is referenced but missing — handshake will fail.\n");
                }
            }
        }
        if (tls.isEmpty()) {
            String traefikTls = annotations.get("traefik.ingress.kubernetes.io/router.tls");
            String traefikEntrypoints = annotations.get("traefik.ingress.kubernetes.io/router.entrypoints");
            boolean traefikAnnotationDrivenTls = "true".equalsIgnoreCase(traefikTls)
                    || (traefikEntrypoints != null && traefikEntrypoints.contains("websecure"));
            if (traefikAnnotationDrivenTls) {
                sb.append("  - NOTE: spec.tls is empty BUT Traefik annotations request TLS\n")
                        .append("    (router.tls=")
                        .append(safe(traefikTls))
                        .append(", router.entrypoints=")
                        .append(safe(traefikEntrypoints))
                        .append("). Traefik resolves the certificate itself (default cert, file\n")
                        .append("    provider, or ACME resolver) — no Kubernetes Secret is required here.\n")
                        .append("    Do NOT flag this as 'missing TLS'. Inspect the Traefik controller\n")
                        .append("    config (entrypoints + certResolver) instead.\n");
            }
        }
        if (anyMissing) {
            sb.append("  - One or more rule backends are missing/empty — those paths will 503.\n");
        }
        if (rules.stream()
                .flatMap(r -> r.getHttp() == null
                        ? java.util.stream.Stream.empty()
                        : Optional.ofNullable(r.getHttp().getPaths())
                                .orElse(Collections.<HTTPIngressPath>emptyList())
                                .stream())
                .anyMatch(p -> p.getPathType() == null)) {
            sb.append(
                    "  - At least one rule has no pathType — required since v1.19. Use Prefix, Exact, or ImplementationSpecific.\n");
        }
        return sb.toString();
    }

    @LlmTool(name = "listIngresses", description = """
                    Use this tool when the operator asks "what is exposed", "show all ingresses",
                    or wants to scan a namespace for routing problems. Pass a namespace or empty
                    string for cluster-wide. Returns one line per Ingress with class, host(s),
                    backend service count, and whether it has a LoadBalancer address. Flags
                    ingresses with no rules, no class, or no LB address.
                    """)
    public String listIngresses(String namespaceOrEmpty) {
        boolean hasNs = namespaceOrEmpty != null && !namespaceOrEmpty.isBlank();
        List<Ingress> ingresses = hasNs
                ? kubernetesClient
                        .network()
                        .v1()
                        .ingresses()
                        .inNamespace(namespaceOrEmpty)
                        .list()
                        .getItems()
                : kubernetesClient
                        .network()
                        .v1()
                        .ingresses()
                        .inAnyNamespace()
                        .list()
                        .getItems();

        StringBuilder sb = new StringBuilder();
        sb.append("=== INGRESSES")
                .append(hasNs ? " (" + namespaceOrEmpty + ")" : " (all namespaces)")
                .append(" ===\n\n");
        if (ingresses.isEmpty()) {
            sb.append("  (none)\n");
            return sb.toString();
        }
        sb.append(String.format(
                "%-30s  %-30s  %-20s  %-40s  %-16s  %s%n",
                "NAMESPACE", "NAME", "CLASS", "HOSTS", "LB-ADDRESS", "BACKENDS"));
        sb.append("-".repeat(160)).append("\n");
        for (Ingress ing : ingresses) {
            IngressSpec spec = ing.getSpec();
            String hosts = Optional.ofNullable(spec).map(IngressSpec::getRules).orElse(Collections.emptyList()).stream()
                    .map(IngressRule::getHost)
                    .filter(h -> h != null && !h.isBlank())
                    .distinct()
                    .collect(Collectors.joining(","));
            int backendCount =
                    Optional.ofNullable(spec).map(IngressSpec::getRules).orElse(Collections.emptyList()).stream()
                            .flatMap(r -> r.getHttp() == null
                                    ? java.util.stream.Stream.empty()
                                    : Optional.ofNullable(r.getHttp().getPaths())
                                            .orElse(Collections.<HTTPIngressPath>emptyList())
                                            .stream())
                            .mapToInt(p -> 1)
                            .sum();
            String lbAddr = Optional.ofNullable(ing.getStatus())
                    .map(s -> s.getLoadBalancer())
                    .map(l -> l.getIngress())
                    .orElse(Collections.emptyList())
                    .stream()
                    .map(li -> li.getIp() != null ? li.getIp() : li.getHostname())
                    .filter(s -> s != null && !s.isBlank())
                    .collect(Collectors.joining(","));
            sb.append(String.format(
                    "%-30s  %-30s  %-20s  %-40s  %-16s  %d%n",
                    ing.getMetadata().getNamespace(),
                    ing.getMetadata().getName(),
                    safe(Optional.ofNullable(spec)
                            .map(IngressSpec::getIngressClassName)
                            .orElse(null)),
                    hosts.length() > 40 ? hosts.substring(0, 37) + "..." : hosts,
                    lbAddr.isBlank() ? "<pending>" : (lbAddr.length() > 16 ? lbAddr.substring(0, 13) + "..." : lbAddr),
                    backendCount));
        }
        return sb.toString();
    }

    @LlmTool(name = "inspectIngressControllers", description = """
                    Use this tool when ingresses do not get an address, fail to route, or when you
                    need to know which ingress controllers are installed. Detects common controllers
                    (ingress-nginx, traefik, haproxy, contour, kong, istio) by their pod labels,
                    lists IngressClasses + their controller field, and reports pod readiness counts.
                    """)
    public String inspectIngressControllers() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== INGRESS CONTROLLERS ===\n\n");

        List<IngressClass> classes =
                kubernetesClient.network().v1().ingressClasses().list().getItems();
        if (classes.isEmpty()) {
            sb.append("[IngressClasses] none defined\n\n");
        } else {
            sb.append("[IngressClasses]\n");
            for (IngressClass ic : classes) {
                boolean isDefault = "true"
                        .equals(Optional.ofNullable(ic.getMetadata().getAnnotations())
                                .map(a -> a.get("ingressclass.kubernetes.io/is-default-class"))
                                .orElse("false"));
                sb.append("  ")
                        .append(ic.getMetadata().getName())
                        .append("  controller=")
                        .append(safe(Optional.ofNullable(ic.getSpec())
                                .map(s -> s.getController())
                                .orElse(null)))
                        .append(isDefault ? "  [DEFAULT]" : "")
                        .append("\n");
            }
            sb.append("\n");
        }

        // Heuristic search for controller pods across the cluster.
        sb.append("[Controller pod heuristics] scanning all namespaces for known controller labels\n");
        List<Pod> pods = kubernetesClient.pods().inAnyNamespace().list().getItems();
        Map<String, int[]> tally = new LinkedHashMap<>();
        for (Pod p : pods) {
            String controller = detectIngressController(p);
            if (controller == null) continue;
            int[] counts = tally.computeIfAbsent(controller, k -> new int[] {0, 0});
            counts[0]++;
            if (isPodReady(p)) counts[1]++;
        }
        if (tally.isEmpty()) {
            sb.append("  no known controller pods detected.\n");
        } else {
            tally.forEach((name, c) -> sb.append("  ")
                    .append(name)
                    .append("  pods=")
                    .append(c[0])
                    .append("  ready=")
                    .append(c[1])
                    .append(c[1] == 0 ? "  ⚠ NONE READY" : c[1] < c[0] ? "  ⚠ DEGRADED" : "")
                    .append("\n"));
        }

        sb.append("\n[SUSPICIONS]\n");
        if (classes.isEmpty() && tally.isEmpty()) {
            sb.append("  - No IngressClass and no detectable controller pods. Ingresses will not work.\n");
            sb.append("    Install one (e.g. ingress-nginx) before creating Ingress resources.\n");
        }
        if (!classes.isEmpty()
                && classes.stream().noneMatch(ic -> "true"
                        .equals(Optional.ofNullable(ic.getMetadata().getAnnotations())
                                .map(a -> a.get("ingressclass.kubernetes.io/is-default-class"))
                                .orElse("false")))) {
            sb.append("  - No default IngressClass. Ingresses without an explicit ingressClassName\n");
            sb.append("    will be ignored. Annotate one with ingressclass.kubernetes.io/is-default-class=true.\n");
        }
        tally.forEach((name, c) -> {
            if (c[1] == 0) {
                sb.append("  - Controller '").append(name).append("' has no ready pods.\n");
                sb.append("    Routing will fail for ingresses bound to this controller.\n");
            }
        });
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Live connectivity probe (creates an ephemeral netshoot debug pod)
    // ──────────────────────────────────────────────────────────────────────────

    private static final String DEBUG_POD_LABEL_KEY = "argus.dev/debug";
    private static final String DEBUG_POD_LABEL_VALUE = "true";
    private static final String DEBUG_POD_REF_KEY = "argus.dev/reference-pod";
    private static final String NETSHOOT_IMAGE = "nicolaka/netshoot:latest";
    private static final String PROBE_CONTAINER_NAME = "netshoot";
    private static final long PROBE_POD_READY_TIMEOUT_SEC = 60L;
    private static final long PROBE_EXEC_TIMEOUT_SEC = 15L;
    private static final long PROBE_POD_SLEEP_SECONDS = 300L;
    private static final long STALE_DEBUG_POD_AGE_SEC = 300L;

    /**
     * Labels stripped from the reference pod when building the probe.
     *
     * <p>Auto-generated by workload controllers; copying them does not help (they are namespaced
     * to a specific controller revision) and risks confusing controllers that watch them.
     */
    private static final Set<String> STRIP_LABELS = Set.of(
            "pod-template-hash",
            "controller-revision-hash",
            "statefulset.kubernetes.io/pod-name",
            "apps.kubernetes.io/pod-index",
            "batch.kubernetes.io/job-name",
            "batch.kubernetes.io/controller-uid",
            "job-name",
            "controller-uid");

    private record ExecResult(int exitCode, String stdout, String stderr, String error) {
        boolean ok() {
            return error == null && exitCode == 0;
        }
    }

    @LlmTool(name = "probeConnectivityFromPod", description = """
                    Use this tool when API-level checks (Services, Endpoints, NetworkPolicies, CNI
                    pods) look healthy but a workload still cannot reach a target host or port, OR
                    when you explicitly need packet-level evidence (DNS lookup, ICMP, TCP connect)
                    rather than API state. Creates a TEMPORARY nicolaka/netshoot debug pod in the
                    same namespace as referencePodName, mirroring its scheduling + network +
                    identity (nodeName, labels, serviceAccount, tolerations, dnsPolicy/dnsConfig,
                    imagePullSecrets, hostNetwork/hostPID/hostIPC, hostAliases, pod-level
                    securityContext, priorityClassName) so NetworkPolicy/DNS/firewall behaviour is
                    identical to the reference workload. Runs nslookup / ping / TCP-connect against
                    targetHost[:targetPort], then ALWAYS deletes the pod (even on errors). Pass
                    targetPort=0 to skip the TCP check. Returns Evidence (raw stdout/stderr +
                    exit codes for each command), a Pattern matrix (DNS vs ICMP vs TCP), and
                    SUSPICIONS. Requires the cluster to be able to pull nicolaka/netshoot and to
                    allow NET_RAW for ping (PSA-restricted namespaces may block this — the tool
                    surfaces that as a finding rather than failing silently).
                    """)
    public String probeConnectivityFromPod(
            String namespace, String referencePodName, String targetHost, int targetPort) {
        if (namespace == null || namespace.isBlank()) {
            return "ERROR: namespace is required.";
        }
        if (referencePodName == null || referencePodName.isBlank()) {
            return "ERROR: referencePodName is required (the pod whose network position should be mimicked).";
        }
        if (targetHost == null || targetHost.isBlank()) {
            return "ERROR: targetHost is required.";
        }

        Pod reference = kubernetesClient
                .pods()
                .inNamespace(namespace)
                .withName(referencePodName)
                .get();
        if (reference == null) {
            return "ERROR: reference pod " + namespace + "/" + referencePodName + " not found.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== CONNECTIVITY PROBE: ")
                .append(namespace)
                .append("/")
                .append(referencePodName)
                .append(" → ")
                .append(targetHost);
        if (targetPort > 0) sb.append(":").append(targetPort);
        sb.append(" ===\n\n");

        sweepStaleDebugPods(namespace, sb);

        String probeName = "argus-netshoot-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Pod probeSpec = buildProbePod(reference, probeName);

        String createdName = null;
        try {
            Pod created = kubernetesClient
                    .pods()
                    .inNamespace(namespace)
                    .resource(probeSpec)
                    .create();
            createdName = created.getMetadata().getName();

            Pod ready;
            try {
                ready = kubernetesClient
                        .pods()
                        .inNamespace(namespace)
                        .withName(createdName)
                        .waitUntilCondition(
                                p -> p != null
                                        && p.getStatus() != null
                                        && "Running".equals(p.getStatus().getPhase()),
                                PROBE_POD_READY_TIMEOUT_SEC,
                                TimeUnit.SECONDS);
            } catch (Exception waitEx) {
                Pod latest = kubernetesClient
                        .pods()
                        .inNamespace(namespace)
                        .withName(createdName)
                        .get();
                appendProbePodNotReady(sb, namespace, createdName, latest, waitEx);
                return sb.toString();
            }

            appendProbePodHeader(sb, ready, reference);

            ExecResult dns = execInPod(
                    namespace,
                    createdName,
                    PROBE_CONTAINER_NAME,
                    List.of("nslookup", targetHost),
                    PROBE_EXEC_TIMEOUT_SEC);
            appendExecBlock(sb, "DNS", "nslookup " + targetHost, dns);

            ExecResult icmp = execInPod(
                    namespace,
                    createdName,
                    PROBE_CONTAINER_NAME,
                    List.of("ping", "-c", "3", "-W", "2", targetHost),
                    PROBE_EXEC_TIMEOUT_SEC);
            appendExecBlock(sb, "ICMP", "ping -c3 -W2 " + targetHost, icmp);

            ExecResult tcp = null;
            if (targetPort > 0) {
                tcp = execInPod(
                        namespace,
                        createdName,
                        PROBE_CONTAINER_NAME,
                        List.of("nc", "-zv", "-w", "5", targetHost, String.valueOf(targetPort)),
                        PROBE_EXEC_TIMEOUT_SEC);
                appendExecBlock(sb, "TCP", "nc -zv -w5 " + targetHost + " " + targetPort, tcp);
            } else {
                sb.append("[TCP] (skipped — targetPort=0)\n\n");
            }

            appendProbePattern(sb, dns, icmp, tcp, targetPort);
            appendProbeSuspicions(sb, dns, icmp, tcp, targetPort, namespace, referencePodName);
            return sb.toString();
        } catch (Exception ex) {
            sb.append("\n⚠ Probe failed: ")
                    .append(ex.getClass().getSimpleName())
                    .append(": ")
                    .append(ex.getMessage())
                    .append("\n");
            log.warn(
                    "probeConnectivityFromPod failed for {}/{} → {}: {}",
                    namespace,
                    referencePodName,
                    targetHost,
                    ex.toString());
            return sb.toString();
        } finally {
            if (createdName != null) {
                try {
                    kubernetesClient
                            .pods()
                            .inNamespace(namespace)
                            .withName(createdName)
                            .withGracePeriod(0L)
                            .delete();
                } catch (Exception cleanupEx) {
                    log.warn("failed to delete probe pod {}/{}: {}", namespace, createdName, cleanupEx.getMessage());
                }
            }
        }
    }

    private Pod buildProbePod(Pod reference, String probeName) {
        ObjectMeta refMeta = reference.getMetadata();
        PodSpec refSpec = reference.getSpec();

        Map<String, String> labels = new LinkedHashMap<>();
        if (refMeta != null && refMeta.getLabels() != null) {
            for (Map.Entry<String, String> e : refMeta.getLabels().entrySet()) {
                if (STRIP_LABELS.contains(e.getKey())) continue;
                labels.put(e.getKey(), e.getValue());
            }
        }
        labels.put(DEBUG_POD_LABEL_KEY, DEBUG_POD_LABEL_VALUE);
        labels.put(DEBUG_POD_REF_KEY, refMeta != null ? safeLabelValue(refMeta.getName()) : "unknown");

        Map<String, String> annotations = new LinkedHashMap<>();
        annotations.put("argus.dev/created-by", "NetworkingDebuggingTools");
        annotations.put("argus.dev/created-at", Instant.now().toString());

        ObjectMeta meta = new ObjectMeta();
        meta.setName(probeName);
        meta.setNamespace(refMeta != null ? refMeta.getNamespace() : null);
        meta.setLabels(labels);
        meta.setAnnotations(annotations);

        // Point ownerReference at the reference pod so workload controllers
        // (DaemonSet / ReplicaSet / StatefulSet / Job) will not adopt the probe pod
        // and delete it as a "duplicate". A pod with any controller-owner is skipped
        // by the controller adoption logic.
        if (refMeta != null && refMeta.getUid() != null && !refMeta.getUid().isBlank()) {
            OwnerReference ownerRef = new OwnerReference();
            ownerRef.setApiVersion("v1");
            ownerRef.setKind("Pod");
            ownerRef.setName(refMeta.getName());
            ownerRef.setUid(refMeta.getUid());
            ownerRef.setController(Boolean.TRUE);
            ownerRef.setBlockOwnerDeletion(Boolean.FALSE);
            meta.setOwnerReferences(List.of(ownerRef));
        }

        PodSpec spec = new PodSpec();
        spec.setRestartPolicy("Never");
        spec.setActiveDeadlineSeconds(300L);
        spec.setTerminationGracePeriodSeconds(1L);
        if (refSpec != null) {
            spec.setNodeName(refSpec.getNodeName());
            spec.setServiceAccountName(refSpec.getServiceAccountName());
            spec.setAutomountServiceAccountToken(refSpec.getAutomountServiceAccountToken());
            spec.setTolerations(refSpec.getTolerations());
            spec.setImagePullSecrets(refSpec.getImagePullSecrets());
            spec.setDnsPolicy(refSpec.getDnsPolicy());
            spec.setDnsConfig(refSpec.getDnsConfig());
            spec.setHostNetwork(refSpec.getHostNetwork());
            spec.setHostPID(refSpec.getHostPID());
            spec.setHostIPC(refSpec.getHostIPC());
            spec.setHostAliases(refSpec.getHostAliases());
            spec.setSecurityContext(refSpec.getSecurityContext());
            spec.setPriorityClassName(refSpec.getPriorityClassName());
        }

        Container container = new Container();
        container.setName(PROBE_CONTAINER_NAME);
        container.setImage(NETSHOOT_IMAGE);
        container.setImagePullPolicy("IfNotPresent");
        container.setCommand(List.of("sleep", String.valueOf(PROBE_POD_SLEEP_SECONDS)));

        SecurityContext containerSc = new SecurityContext();
        Capabilities caps = new Capabilities();
        caps.setAdd(List.of("NET_RAW"));
        containerSc.setCapabilities(caps);
        container.setSecurityContext(containerSc);

        ResourceRequirements resources = new ResourceRequirements();
        Map<String, Quantity> requests = new LinkedHashMap<>();
        requests.put("cpu", new Quantity("10m"));
        requests.put("memory", new Quantity("32Mi"));
        Map<String, Quantity> limits = new LinkedHashMap<>();
        limits.put("cpu", new Quantity("200m"));
        limits.put("memory", new Quantity("128Mi"));
        resources.setRequests(requests);
        resources.setLimits(limits);
        container.setResources(resources);

        spec.setContainers(List.of(container));

        Pod pod = new Pod();
        pod.setMetadata(meta);
        pod.setSpec(spec);
        return pod;
    }

    /** Kubernetes label values must be ≤63 chars and match a strict regex; we only need a hint. */
    private String safeLabelValue(String v) {
        if (v == null) return "unknown";
        String trimmed = v.replaceAll("[^A-Za-z0-9_.-]", "-");
        return trimmed.length() > 63 ? trimmed.substring(0, 63) : trimmed;
    }

    private void sweepStaleDebugPods(String namespace, StringBuilder sb) {
        try {
            List<Pod> stale = kubernetesClient
                    .pods()
                    .inNamespace(namespace)
                    .withLabel(DEBUG_POD_LABEL_KEY, DEBUG_POD_LABEL_VALUE)
                    .list()
                    .getItems();
            Instant cutoff = Instant.now().minusSeconds(STALE_DEBUG_POD_AGE_SEC);
            int deleted = 0;
            for (Pod p : stale) {
                String ts = p.getMetadata().getCreationTimestamp();
                if (ts == null) continue;
                Instant created;
                try {
                    created = Instant.parse(ts);
                } catch (Exception parseEx) {
                    continue;
                }
                if (created.isBefore(cutoff)) {
                    try {
                        kubernetesClient
                                .pods()
                                .inNamespace(namespace)
                                .withName(p.getMetadata().getName())
                                .withGracePeriod(0L)
                                .delete();
                        deleted++;
                    } catch (Exception ignored) {
                        // best-effort sweep
                    }
                }
            }
            if (deleted > 0) {
                sb.append("[Cleanup] removed ")
                        .append(deleted)
                        .append(" stale argus debug pod(s) older than ")
                        .append(STALE_DEBUG_POD_AGE_SEC)
                        .append("s.\n\n");
            }
        } catch (Exception e) {
            log.debug("stale debug pod sweep failed for ns {}: {}", namespace, e.getMessage());
        }
    }

    private void appendProbePodHeader(StringBuilder sb, Pod probe, Pod reference) {
        ObjectMeta pm = probe.getMetadata();
        PodSpec ps = probe.getSpec();
        sb.append("[Probe pod] ")
                .append(pm.getName())
                .append("  node=")
                .append(safe(ps == null ? null : ps.getNodeName()))
                .append("  sa=")
                .append(safe(ps == null ? null : ps.getServiceAccountName()))
                .append("  hostNetwork=")
                .append(ps != null && Boolean.TRUE.equals(ps.getHostNetwork()))
                .append("  dnsPolicy=")
                .append(safe(ps == null ? null : ps.getDnsPolicy()))
                .append("\n");
        ObjectMeta rm = reference.getMetadata();
        PodSpec rs = reference.getSpec();
        sb.append("[Mirrored from] ")
                .append(rm.getNamespace())
                .append("/")
                .append(rm.getName())
                .append("  labels=")
                .append(Optional.ofNullable(rm.getLabels()).orElse(Collections.emptyMap()))
                .append("  serviceAccount=")
                .append(safe(rs == null ? null : rs.getServiceAccountName()))
                .append("\n");
        List<OwnerReference> owners =
                Optional.ofNullable(pm.getOwnerReferences()).orElse(Collections.emptyList());
        if (!owners.isEmpty()) {
            OwnerReference o = owners.get(0);
            sb.append("[Adoption guard] ownerReference -> ")
                    .append(safe(o.getKind()))
                    .append("/")
                    .append(safe(o.getName()))
                    .append(" (controller=true) — workload controllers will not claim this probe.\n");
        }
        Map<String, String> refLabels = Optional.ofNullable(rm.getLabels()).orElse(Collections.emptyMap());
        List<String> stripped = refLabels.keySet().stream()
                .filter(STRIP_LABELS::contains)
                .sorted()
                .toList();
        if (!stripped.isEmpty()) {
            sb.append("[Stripped labels] ")
                    .append(String.join(", ", stripped))
                    .append(" (auto-managed by workload controllers; not copied to the probe)\n");
        }
        sb.append("\n");
    }

    private void appendExecBlock(StringBuilder sb, String tag, String cmd, ExecResult r) {
        sb.append("[").append(tag).append("] ").append(cmd).append("\n");
        sb.append("  exitCode=").append(r.exitCode());
        if (r.error() != null) {
            sb.append("  error=").append(r.error());
        }
        sb.append("\n");
        if (!r.stdout().isBlank()) {
            sb.append("  stdout:\n");
            appendIndented(sb, r.stdout(), "    ");
        }
        if (!r.stderr().isBlank()) {
            sb.append("  stderr:\n");
            appendIndented(sb, r.stderr(), "    ");
        }
        sb.append("\n");
    }

    private void appendIndented(StringBuilder sb, String text, String prefix) {
        for (String line : text.split("\\R")) {
            sb.append(prefix).append(line).append("\n");
        }
    }

    private void appendProbePodNotReady(
            StringBuilder sb, String namespace, String podName, Pod latest, Exception waitEx) {
        sb.append("[Probe pod] ")
                .append(podName)
                .append("  ⚠ NEVER REACHED Running within ")
                .append(PROBE_POD_READY_TIMEOUT_SEC)
                .append("s\n");
        sb.append("  wait error: ")
                .append(waitEx.getClass().getSimpleName())
                .append(": ")
                .append(waitEx.getMessage())
                .append("\n");
        if (latest != null && latest.getStatus() != null) {
            PodStatus st = latest.getStatus();
            sb.append("  phase=").append(safe(st.getPhase())).append("\n");
            if (st.getReason() != null)
                sb.append("  reason=").append(st.getReason()).append("\n");
            if (st.getMessage() != null)
                sb.append("  message=").append(st.getMessage()).append("\n");
            List<PodCondition> conds = Optional.ofNullable(st.getConditions()).orElse(Collections.emptyList());
            if (!conds.isEmpty()) {
                sb.append("  conditions:\n");
                for (PodCondition c : conds) {
                    sb.append("    - ")
                            .append(c.getType())
                            .append("=")
                            .append(c.getStatus())
                            .append(c.getReason() != null ? "  reason=" + c.getReason() : "")
                            .append(c.getMessage() != null ? "  message=" + c.getMessage() : "")
                            .append("\n");
                }
            }
            List<ContainerStatus> cstats =
                    Optional.ofNullable(st.getContainerStatuses()).orElse(Collections.emptyList());
            for (ContainerStatus cs : cstats) {
                ContainerStateWaiting w =
                        cs.getState() == null ? null : cs.getState().getWaiting();
                if (w != null) {
                    sb.append("  container[")
                            .append(cs.getName())
                            .append("].waiting: ")
                            .append(safe(w.getReason()))
                            .append(" — ")
                            .append(safe(w.getMessage()))
                            .append("\n");
                }
            }
        }
        List<Event> events = fetchObjectEvents(namespace, podName);
        appendEvents(sb, events, "Probe Pod Events");
        sb.append("\n[SUSPICIONS]\n");
        sb.append("  - Probe pod could not start. Likely causes:\n");
        sb.append("      • image pull failure (cluster cannot reach docker.io for ")
                .append(NETSHOOT_IMAGE)
                .append(")\n");
        sb.append("      • PodSecurityAdmission rejection (NET_RAW capability or hostNetwork blocked)\n");
        sb.append("      • scheduling failure (nodeName from reference is cordoned or gone)\n");
        sb.append("      • ResourceQuota / LimitRange in the namespace blocked the pod\n");
        sb.append("    Inspect the events above and consider running with a different reference pod.\n");
    }

    private void appendProbePattern(StringBuilder sb, ExecResult dns, ExecResult icmp, ExecResult tcp, int targetPort) {
        sb.append("[Pattern]\n");
        sb.append("  DNS=").append(dns.ok() ? "ok" : "fail");
        sb.append("  ICMP=").append(icmp.ok() ? "ok" : "fail");
        if (targetPort > 0 && tcp != null) {
            sb.append("  TCP=").append(tcp.ok() ? "ok" : "fail");
        } else {
            sb.append("  TCP=skipped");
        }
        sb.append("\n\n");
    }

    private void appendProbeSuspicions(
            StringBuilder sb,
            ExecResult dns,
            ExecResult icmp,
            ExecResult tcp,
            int targetPort,
            String namespace,
            String referencePodName) {
        sb.append("[SUSPICIONS]\n");
        boolean dnsOk = dns.ok();
        boolean icmpOk = icmp.ok();
        boolean tcpOk = tcp != null && tcp.ok();
        boolean tcpRan = targetPort > 0 && tcp != null;

        if (containsPermissionDenied(icmp)) {
            sb.append("  - ping failed with EPERM/operation-not-permitted — NET_RAW was dropped\n");
            sb.append("    (PodSecurityAdmission 'restricted' or 'baseline' likely). ICMP result is\n");
            sb.append("    not reliable; rely on the TCP probe and DNS check instead.\n");
        }

        if (!dnsOk && !icmpOk && !tcpOk) {
            sb.append("  - All checks failed. DNS is broken at minimum. Investigate CoreDNS/kube-dns\n");
            sb.append("    health, the pod's dnsPolicy and dnsConfig, and any egress NetworkPolicy.\n");
            sb.append("    Next: inspectServiceConnectivity kube-system/kube-dns, then\n");
            sb.append("    inspectNetworkPoliciesForPod ")
                    .append(namespace)
                    .append(" ")
                    .append(referencePodName)
                    .append(".\n");
            return;
        }

        if (!dnsOk) {
            sb.append("  - DNS lookup failed. Likely CoreDNS unreachable, search domain wrong,\n");
            sb.append("    NetworkPolicy egress blocking UDP/53, or the name simply doesn't exist.\n");
            sb.append("    Next: inspectServiceConnectivity kube-system/kube-dns.\n");
        }

        if (dnsOk && tcpRan && !tcpOk) {
            sb.append("  - DNS resolves but TCP connect failed. Most likely culprits:\n");
            sb.append("      • NetworkPolicy egress (or peer ingress) is dropping the flow\n");
            sb.append("      • Target Service has zero ready endpoints on that port\n");
            sb.append("      • A node-level firewall (iptables/eBPF) or cloud SG is dropping packets\n");
            sb.append("    Next: inspectNetworkPoliciesForPod ")
                    .append(namespace)
                    .append(" ")
                    .append(referencePodName)
                    .append(", then inspectServiceConnectivity for the target Service.\n");
        }

        if (dnsOk && !icmpOk && tcpRan && tcpOk) {
            sb.append("  - DNS + TCP both succeed; only ICMP fails. ICMP is commonly filtered by\n");
            sb.append("    cloud SDN/SG layers and by some CNIs — treat this as benign.\n");
        }

        if (dnsOk && icmpOk && tcpRan && !tcpOk) {
            sb.append("  - Host is reachable (ICMP) but the TCP port refuses. The target Service\n");
            sb.append("    likely has no process listening on that port, the pod is not Ready, or\n");
            sb.append("    a targetPort/containerPort mismatch is sending traffic to the wrong port.\n");
        }

        if (dnsOk && tcpRan && tcpOk) {
            sb.append("  - Packet path looks good. If the application still fails, the issue is\n");
            sb.append("    application-layer (TLS handshake, HTTP host header, auth, app crash).\n");
        }

        if (!tcpRan && dnsOk) {
            sb.append("  - No TCP port supplied — only DNS + ICMP were checked. Re-run with the\n");
            sb.append("    target port to validate the actual service port.\n");
        }
    }

    private boolean containsPermissionDenied(ExecResult r) {
        String haystack = (r.stderr() + " " + r.stdout()).toLowerCase();
        return haystack.contains("operation not permitted")
                || haystack.contains("permission denied")
                || haystack.contains("socket: permission denied");
    }

    private ExecResult execInPod(
            String namespace, String podName, String container, List<String> cmd, long timeoutSec) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (ExecWatch watch = kubernetesClient
                .pods()
                .inNamespace(namespace)
                .withName(podName)
                .inContainer(container)
                .writingOutput(out)
                .writingError(err)
                .exec(cmd.toArray(new String[0]))) {
            Integer code = watch.exitCode().get(timeoutSec, TimeUnit.SECONDS);
            return new ExecResult(
                    code == null ? -1 : code,
                    out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8),
                    null);
        } catch (TimeoutException te) {
            return new ExecResult(
                    -1,
                    out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8),
                    "timeout after " + timeoutSec + "s");
        } catch (KubernetesClientException kce) {
            return new ExecResult(
                    -1,
                    out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8),
                    "KubernetesClientException: " + kce.getMessage());
        } catch (Exception e) {
            return new ExecResult(
                    -1,
                    out.toString(StandardCharsets.UTF_8),
                    err.toString(StandardCharsets.UTF_8),
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // NetworkPolicy
    // ──────────────────────────────────────────────────────────────────────────

    @LlmTool(name = "inspectNetworkPoliciesForPod", description = """
                    Use this tool when traffic to or from a specific pod is unexpectedly blocked,
                    or when you suspect a NetworkPolicy is the culprit. Returns all NetworkPolicies
                    in the pod's namespace whose podSelector matches this pod, expanded with their
                    policyTypes, ingress/egress rules (peers + ports), and a SUSPICIONS block that
                    detects default-deny posture, missing egress for DNS, or an empty selector that
                    catches the entire namespace.
                    """)
    public String inspectNetworkPoliciesForPod(String namespace, String podName) {
        Pod pod =
                kubernetesClient.pods().inNamespace(namespace).withName(podName).get();
        if (pod == null) {
            return "ERROR: pod " + namespace + "/" + podName + " not found.";
        }

        Map<String, String> podLabels =
                Optional.ofNullable(pod.getMetadata().getLabels()).orElse(Collections.emptyMap());

        List<NetworkPolicy> all = kubernetesClient
                .network()
                .v1()
                .networkPolicies()
                .inNamespace(namespace)
                .list()
                .getItems();

        List<NetworkPolicy> matching = all.stream()
                .filter(np -> labelSelectorMatches(
                        Optional.ofNullable(np.getSpec())
                                .map(NetworkPolicySpec::getPodSelector)
                                .orElse(null),
                        podLabels))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("=== NETWORK POLICIES FOR POD: ")
                .append(namespace)
                .append("/")
                .append(podName)
                .append(" ===\n\n");
        sb.append("  pod labels: ").append(podLabels).append("\n");
        sb.append("  policies in namespace: ").append(all.size()).append("\n");
        sb.append("  policies matching this pod: ").append(matching.size()).append("\n\n");

        if (matching.isEmpty()) {
            sb.append("[Result] no NetworkPolicy selects this pod.\n");
            sb.append("  - If the namespace has NO NetworkPolicies, all traffic is allowed (default-allow).\n");
            sb.append("  - If other policies exist but none match, this pod is still default-allow.\n");
            return sb.toString();
        }

        boolean ingressIsolated = false;
        boolean egressIsolated = false;
        boolean dnsAllowed = false;
        for (NetworkPolicy np : matching) {
            sb.append("--- NetworkPolicy: ").append(np.getMetadata().getName()).append(" ---\n");
            NetworkPolicySpec spec = np.getSpec();
            List<String> types = Optional.ofNullable(spec)
                    .map(NetworkPolicySpec::getPolicyTypes)
                    .orElse(Collections.emptyList());
            sb.append("  policyTypes: ")
                    .append(types.isEmpty() ? "[implicit: Ingress]" : types)
                    .append("\n");
            if (types.contains("Ingress") || types.isEmpty()) ingressIsolated = true;
            if (types.contains("Egress")) egressIsolated = true;

            List<NetworkPolicyIngressRule> ingress =
                    Optional.ofNullable(spec).map(NetworkPolicySpec::getIngress).orElse(Collections.emptyList());
            sb.append("  ingress rules: ").append(ingress.size()).append("\n");
            for (NetworkPolicyIngressRule r : ingress) {
                sb.append("    from: ").append(formatPeers(r.getFrom())).append("\n");
                sb.append("    ports: ").append(formatNpPorts(r.getPorts())).append("\n");
            }
            if (ingress.isEmpty() && (types.contains("Ingress") || types.isEmpty())) {
                sb.append("    (empty list with Ingress in policyTypes => DENY ALL ingress)\n");
            }

            List<NetworkPolicyEgressRule> egress =
                    Optional.ofNullable(spec).map(NetworkPolicySpec::getEgress).orElse(Collections.emptyList());
            sb.append("  egress rules: ").append(egress.size()).append("\n");
            for (NetworkPolicyEgressRule r : egress) {
                String peers = formatPeers(r.getTo());
                String ports = formatNpPorts(r.getPorts());
                sb.append("    to:    ").append(peers).append("\n");
                sb.append("    ports: ").append(ports).append("\n");
                if (peers.contains("kube-system") || peers.contains("k8s-app=kube-dns")) dnsAllowed = true;
                if (ports.contains(":53")) dnsAllowed = true;
            }
            if (egress.isEmpty() && types.contains("Egress")) {
                sb.append("    (empty list with Egress in policyTypes => DENY ALL egress)\n");
            }
            sb.append("\n");
        }

        sb.append("[SUSPICIONS]\n");
        if (ingressIsolated) {
            sb.append("  - Pod is INGRESS-isolated. Only explicitly allowed peers can reach it.\n");
            sb.append("    Verify the source pod/namespace labels match a 'from' rule above.\n");
        }
        if (egressIsolated) {
            sb.append("  - Pod is EGRESS-isolated. Outbound traffic must match an allow rule.\n");
            if (!dnsAllowed) {
                sb.append("  - No obvious egress rule for DNS (kube-system / port 53). Pod cannot resolve\n");
                sb.append("    Service names — typical symptom is 'no such host' from the application.\n");
            }
        }
        long catchAll = matching.stream()
                .filter(np -> {
                    LabelSelector sel = Optional.ofNullable(np.getSpec())
                            .map(NetworkPolicySpec::getPodSelector)
                            .orElse(null);
                    return sel == null
                            || ((sel.getMatchLabels() == null
                                            || sel.getMatchLabels().isEmpty())
                                    && (sel.getMatchExpressions() == null
                                            || sel.getMatchExpressions().isEmpty()));
                })
                .count();
        if (catchAll > 0) {
            sb.append("  - ")
                    .append(catchAll)
                    .append(" policy/policies use an empty podSelector ({}) — they apply to EVERY pod\n");
            sb.append("    in this namespace. A common default-deny pattern.\n");
        }
        return sb.toString();
    }

    @LlmTool(name = "listNetworkPolicies", description = """
                    Use this tool to scan a namespace for NetworkPolicies and quickly understand
                    its isolation posture. Pass a namespace or empty string for cluster-wide.
                    Returns each policy with its selector, policyTypes, and rule counts, and flags
                    namespaces operating under a default-deny rule.
                    """)
    public String listNetworkPolicies(String namespaceOrEmpty) {
        boolean hasNs = namespaceOrEmpty != null && !namespaceOrEmpty.isBlank();
        List<NetworkPolicy> nps = hasNs
                ? kubernetesClient
                        .network()
                        .v1()
                        .networkPolicies()
                        .inNamespace(namespaceOrEmpty)
                        .list()
                        .getItems()
                : kubernetesClient
                        .network()
                        .v1()
                        .networkPolicies()
                        .inAnyNamespace()
                        .list()
                        .getItems();

        StringBuilder sb = new StringBuilder();
        sb.append("=== NETWORK POLICIES")
                .append(hasNs ? " (" + namespaceOrEmpty + ")" : " (all namespaces)")
                .append(" ===\n\n");
        if (nps.isEmpty()) {
            sb.append("  (none)\n");
            sb.append("  Namespace is default-allow: all pods can talk to all pods.\n");
            return sb.toString();
        }
        sb.append(String.format(
                "%-30s  %-40s  %-25s  %-20s  %-7s  %-7s%n",
                "NAMESPACE", "NAME", "POD-SELECTOR", "POLICY-TYPES", "INGRESS", "EGRESS"));
        sb.append("-".repeat(140)).append("\n");
        for (NetworkPolicy np : nps) {
            NetworkPolicySpec spec = np.getSpec();
            String sel =
                    spec == null || spec.getPodSelector() == null ? "{}" : formatLabelSelector(spec.getPodSelector());
            String types = Optional.ofNullable(spec)
                    .map(NetworkPolicySpec::getPolicyTypes)
                    .orElse(Collections.emptyList())
                    .toString();
            int ingressCount = Optional.ofNullable(spec)
                    .map(NetworkPolicySpec::getIngress)
                    .orElse(Collections.emptyList())
                    .size();
            int egressCount = Optional.ofNullable(spec)
                    .map(NetworkPolicySpec::getEgress)
                    .orElse(Collections.emptyList())
                    .size();
            sb.append(String.format(
                    "%-30s  %-40s  %-25s  %-20s  %-7d  %-7d%n",
                    np.getMetadata().getNamespace(),
                    truncate(np.getMetadata().getName(), 40),
                    truncate(sel, 25),
                    truncate(types, 20),
                    ingressCount,
                    egressCount));
        }

        sb.append("\n[SUSPICIONS]\n");
        Map<String, Long> byNs = nps.stream()
                .filter(np -> {
                    LabelSelector s = Optional.ofNullable(np.getSpec())
                            .map(NetworkPolicySpec::getPodSelector)
                            .orElse(null);
                    return s == null
                            || ((s.getMatchLabels() == null
                                            || s.getMatchLabels().isEmpty())
                                    && (s.getMatchExpressions() == null
                                            || s.getMatchExpressions().isEmpty()));
                })
                .collect(Collectors.groupingBy(np -> np.getMetadata().getNamespace(), Collectors.counting()));
        if (byNs.isEmpty()) {
            sb.append("  - No catch-all policies. Isolation is per-pod (label-targeted).\n");
        } else {
            byNs.forEach((ns, count) -> sb.append("  - Namespace '")
                    .append(ns)
                    .append("' has ")
                    .append(count)
                    .append(" catch-all policy/policies — likely a default-deny posture.\n"));
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Pod networking
    // ──────────────────────────────────────────────────────────────────────────

    @LlmTool(name = "inspectPodNetworking", description = """
                    Use this tool for pod-level network problems: missing pod IP, hostNetwork
                    side effects, wrong dnsPolicy, probe failures preventing endpoints, container
                    port misconfigurations, or to confirm which node and CNI a pod runs on.
                    Returns podIP/hostIP/nodeName, hostNetwork flag, dnsPolicy + dnsConfig, hostAliases,
                    per-container ports + readiness/liveness/startup probes, container ready state,
                    and recent networking-related events.
                    """)
    public String inspectPodNetworking(String namespace, String podName) {
        Pod pod =
                kubernetesClient.pods().inNamespace(namespace).withName(podName).get();
        if (pod == null) {
            return "ERROR: pod " + namespace + "/" + podName + " not found.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== POD NETWORKING: ")
                .append(namespace)
                .append("/")
                .append(podName)
                .append(" ===\n\n");

        PodSpec spec = pod.getSpec();
        PodStatus status = pod.getStatus();

        sb.append("  nodeName:     ")
                .append(safe(Optional.ofNullable(spec).map(PodSpec::getNodeName).orElse(null)))
                .append("\n");
        sb.append("  podIP:        ")
                .append(safe(
                        Optional.ofNullable(status).map(PodStatus::getPodIP).orElse(null)))
                .append("\n");
        sb.append("  hostIP:       ")
                .append(safe(
                        Optional.ofNullable(status).map(PodStatus::getHostIP).orElse(null)))
                .append("\n");
        List<PodIP> podIPs =
                Optional.ofNullable(status).map(PodStatus::getPodIPs).orElse(Collections.emptyList());
        if (podIPs.size() > 1) {
            sb.append("  podIPs:       ")
                    .append(podIPs.stream().map(PodIP::getIp).collect(Collectors.joining(", ")))
                    .append("\n");
        }
        sb.append("  hostNetwork:  ")
                .append(Optional.ofNullable(spec).map(PodSpec::getHostNetwork).orElse(false))
                .append("\n");
        sb.append("  hostPID:      ")
                .append(Optional.ofNullable(spec).map(PodSpec::getHostPID).orElse(false))
                .append("\n");
        sb.append("  dnsPolicy:    ")
                .append(safe(
                        Optional.ofNullable(spec).map(PodSpec::getDnsPolicy).orElse(null)))
                .append("\n");

        PodDNSConfig dns = Optional.ofNullable(spec).map(PodSpec::getDnsConfig).orElse(null);
        if (dns != null) {
            sb.append("  dnsConfig:\n");
            sb.append("    nameservers: ")
                    .append(Optional.ofNullable(dns.getNameservers()).orElse(Collections.emptyList()))
                    .append("\n");
            sb.append("    searches:    ")
                    .append(Optional.ofNullable(dns.getSearches()).orElse(Collections.emptyList()))
                    .append("\n");
            if (dns.getOptions() != null && !dns.getOptions().isEmpty()) {
                sb.append("    options:     ")
                        .append(dns.getOptions().stream()
                                .map(o -> o.getName() + (o.getValue() == null ? "" : "=" + o.getValue()))
                                .collect(Collectors.joining(", ")))
                        .append("\n");
            }
        }

        List<HostAlias> aliases =
                Optional.ofNullable(spec).map(PodSpec::getHostAliases).orElse(Collections.emptyList());
        if (!aliases.isEmpty()) {
            sb.append("  hostAliases:\n");
            for (HostAlias a : aliases) {
                sb.append("    ")
                        .append(safe(a.getIp()))
                        .append(" -> ")
                        .append(Optional.ofNullable(a.getHostnames()).orElse(Collections.emptyList()))
                        .append("\n");
            }
        }

        // Container ports + probes
        List<Container> containers =
                Optional.ofNullable(spec).map(PodSpec::getContainers).orElse(Collections.emptyList());
        List<ContainerStatus> statuses =
                Optional.ofNullable(status).map(PodStatus::getContainerStatuses).orElse(Collections.emptyList());
        Map<String, ContainerStatus> byName =
                statuses.stream().collect(Collectors.toMap(ContainerStatus::getName, cs -> cs, (a, b) -> a));

        for (Container c : containers) {
            sb.append("  container: ").append(c.getName()).append("\n");
            List<ContainerPort> ports = Optional.ofNullable(c.getPorts()).orElse(Collections.emptyList());
            if (ports.isEmpty()) {
                sb.append("    ports: <none declared>\n");
            } else {
                sb.append("    ports:\n");
                for (ContainerPort p : ports) {
                    sb.append("      - name=")
                            .append(safe(p.getName()))
                            .append("  containerPort=")
                            .append(p.getContainerPort())
                            .append("  protocol=")
                            .append(safe(p.getProtocol()));
                    if (p.getHostPort() != null) sb.append("  hostPort=").append(p.getHostPort());
                    sb.append("\n");
                }
            }
            sb.append("    readinessProbe: ")
                    .append(formatProbe(c.getReadinessProbe()))
                    .append("\n");
            sb.append("    livenessProbe:  ")
                    .append(formatProbe(c.getLivenessProbe()))
                    .append("\n");
            sb.append("    startupProbe:   ")
                    .append(formatProbe(c.getStartupProbe()))
                    .append("\n");
            ContainerStatus cs = byName.get(c.getName());
            if (cs != null) {
                sb.append("    ready=")
                        .append(cs.getReady())
                        .append("  restartCount=")
                        .append(cs.getRestartCount());
                ContainerState st = cs.getState();
                if (st != null && st.getWaiting() != null) {
                    sb.append("  waiting=").append(safe(st.getWaiting().getReason()));
                }
                sb.append("\n");
            }
        }

        // Pod conditions
        List<PodCondition> conds =
                Optional.ofNullable(status).map(PodStatus::getConditions).orElse(Collections.emptyList());
        if (!conds.isEmpty()) {
            sb.append("  conditions:\n");
            for (PodCondition c : conds) {
                sb.append("    ").append(c.getType()).append("=").append(c.getStatus());
                if (c.getReason() != null) sb.append("  reason=").append(c.getReason());
                if (c.getMessage() != null) sb.append("  msg=").append(c.getMessage());
                sb.append("\n");
            }
        }

        List<Event> events = fetchObjectEvents(namespace, podName);
        appendEvents(sb, events, "Pod Events");

        sb.append("\n[SUSPICIONS]\n");
        boolean any = false;
        String podIP = Optional.ofNullable(status).map(PodStatus::getPodIP).orElse(null);
        if (podIP == null || podIP.isBlank()) {
            sb.append("  - Pod has no IP. CNI failed to allocate one — check kubelet logs and CNI plugin.\n");
            any = true;
        }
        if (Boolean.TRUE.equals(
                Optional.ofNullable(spec).map(PodSpec::getHostNetwork).orElse(false))) {
            sb.append("  - hostNetwork=true: pod shares the node's network namespace. Service routing\n");
            sb.append("    via ClusterIP will not work the same way; ports must not collide on the node.\n");
            any = true;
        }
        boolean readinessFailing = statuses.stream().anyMatch(cs -> !Boolean.TRUE.equals(cs.getReady()));
        if (readinessFailing) {
            sb.append("  - At least one container is NotReady — it will be excluded from Service endpoints.\n");
            any = true;
        }
        for (Container c : containers) {
            List<ContainerPort> cports = Optional.ofNullable(c.getPorts()).orElse(Collections.emptyList());
            if (cports.isEmpty()) {
                sb.append("  - Container '")
                        .append(c.getName())
                        .append("' declares no ports. A Service with a named targetPort cannot bind to it.\n");
                any = true;
            }
        }
        if (!any) {
            sb.append("  - Pod networking looks healthy at the API level. If traffic still fails,\n");
            sb.append("    check NetworkPolicies, kube-proxy/iptables on the node, and CNI.\n");
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // DNS health
    // ──────────────────────────────────────────────────────────────────────────

    @LlmTool(name = "inspectDNSHealth", description = """
                    Use this tool when pods report "no such host", "lookup ... no such host",
                    intermittent DNS failures, or slow service discovery. Inspects the CoreDNS
                    Deployment + pods in kube-system, the kube-dns Service and its endpoints, and
                    the CoreDNS Corefile ConfigMap. Returns ready replica counts, recent restarts,
                    nameserver IP, and a SUSPICIONS block for missing/degraded DNS.
                    """)
    public String inspectDNSHealth() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== DNS HEALTH (CoreDNS / kube-dns) ===\n\n");

        String dnsNs = "kube-system";

        Service kubeDns = kubernetesClient
                .services()
                .inNamespace(dnsNs)
                .withName("kube-dns")
                .get();
        if (kubeDns == null) {
            sb.append("[Service] kube-system/kube-dns NOT FOUND\n");
        } else {
            sb.append("[Service kube-system/kube-dns]\n");
            ServiceSpec ss = kubeDns.getSpec();
            sb.append("  clusterIP: ")
                    .append(safe(ss == null ? null : ss.getClusterIP()))
                    .append("\n");
            sb.append("  ports: ")
                    .append(formatServicePorts(
                            Optional.ofNullable(ss).map(ServiceSpec::getPorts).orElse(Collections.emptyList())))
                    .append("\n");
            Endpoints eps = kubernetesClient
                    .endpoints()
                    .inNamespace(dnsNs)
                    .withName("kube-dns")
                    .get();
            long ready = countReadyEndpoints(eps);
            long notReady = countNotReadyEndpoints(eps);
            sb.append("  endpoints: ready=").append(ready).append("  notReady=").append(notReady);
            if (ready == 0) sb.append("  ⚠ NO READY DNS ENDPOINTS");
            sb.append("\n");
        }
        sb.append("\n");

        // CoreDNS Deployment + pods
        Deployment coreDns = kubernetesClient
                .apps()
                .deployments()
                .inNamespace(dnsNs)
                .withName("coredns")
                .get();
        if (coreDns == null) {
            // older clusters used 'kube-dns' deployment
            coreDns = kubernetesClient
                    .apps()
                    .deployments()
                    .inNamespace(dnsNs)
                    .withName("kube-dns")
                    .get();
        }
        if (coreDns == null) {
            sb.append("[Deployment] coredns / kube-dns NOT FOUND in kube-system\n");
        } else {
            sb.append("[Deployment kube-system/")
                    .append(coreDns.getMetadata().getName())
                    .append("]\n");
            Integer desired = Optional.ofNullable(coreDns.getSpec())
                    .map(s -> s.getReplicas())
                    .orElse(0);
            Integer ready = Optional.ofNullable(coreDns.getStatus())
                    .map(s -> s.getReadyReplicas())
                    .orElse(0);
            sb.append("  replicas: desired=").append(desired).append("  ready=").append(ready);
            if (ready == null || ready == 0) sb.append("  ⚠ NO READY REPLICAS");
            sb.append("\n");

            // Inspect underlying pods for restart loops
            Map<String, String> sel = Optional.ofNullable(coreDns.getSpec())
                    .map(s -> s.getSelector())
                    .map(LabelSelector::getMatchLabels)
                    .orElse(Collections.emptyMap());
            if (!sel.isEmpty()) {
                List<Pod> dnsPods = kubernetesClient
                        .pods()
                        .inNamespace(dnsNs)
                        .withLabels(sel)
                        .list()
                        .getItems();
                for (Pod p : dnsPods) {
                    int restarts = Optional.ofNullable(p.getStatus())
                            .map(PodStatus::getContainerStatuses)
                            .orElse(Collections.emptyList())
                            .stream()
                            .mapToInt(ContainerStatus::getRestartCount)
                            .sum();
                    boolean ready2 = isPodReady(p);
                    sb.append("    pod ")
                            .append(p.getMetadata().getName())
                            .append("  ready=")
                            .append(ready2)
                            .append("  restarts=")
                            .append(restarts);
                    if (restarts > 3) sb.append("  ⚠ HIGH RESTARTS");
                    sb.append("\n");
                }
            }
        }
        sb.append("\n");

        // Corefile
        ConfigMap corefile = kubernetesClient
                .configMaps()
                .inNamespace(dnsNs)
                .withName("coredns")
                .get();
        if (corefile == null) {
            sb.append("[ConfigMap] kube-system/coredns NOT FOUND\n");
        } else {
            sb.append("[Corefile kube-system/coredns]\n");
            String body = Optional.ofNullable(corefile.getData())
                    .map(d -> d.get("Corefile"))
                    .orElse(null);
            if (body == null) {
                sb.append("  (no 'Corefile' key)\n");
            } else {
                for (String line : body.split("\n")) {
                    sb.append("    ").append(line).append("\n");
                }
            }
        }

        sb.append("\n[SUSPICIONS]\n");
        Service kd = kubeDns; // capture
        if (kd == null) {
            sb.append("  - kube-dns Service is missing. Cluster DNS will not work; reinstall CoreDNS.\n");
        } else {
            Endpoints eps = kubernetesClient
                    .endpoints()
                    .inNamespace(dnsNs)
                    .withName("kube-dns")
                    .get();
            if (countReadyEndpoints(eps) == 0) {
                sb.append("  - kube-dns has no ready endpoints. All pods will see 'no such host'.\n");
                sb.append("    Investigate the CoreDNS pods and their readiness probes.\n");
            }
        }
        if (coreDns != null) {
            Integer ready = Optional.ofNullable(coreDns.getStatus())
                    .map(s -> s.getReadyReplicas())
                    .orElse(0);
            Integer desired = Optional.ofNullable(coreDns.getSpec())
                    .map(s -> s.getReplicas())
                    .orElse(0);
            if (desired != null && desired > 0 && (ready == null || ready < desired)) {
                sb.append("  - CoreDNS is below desired replicas. Consider scaling, anti-affinity,\n");
                sb.append("    or checking for OOM (CoreDNS is sensitive to memory limits at large scale).\n");
            }
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Cluster network infrastructure
    // ──────────────────────────────────────────────────────────────────────────

    @LlmTool(name = "inspectClusterNetworkInfrastructure", description = """
                    Use this tool to get a one-shot overview of the cluster's network plane: which
                    CNI is installed (calico, cilium, flannel, weave, kube-router, antrea), the
                    state of kube-proxy, per-node PodCIDRs and InternalIPs, and any LoadBalancer
                    providers visible (metallb, cilium-lb). Use early in any cross-cluster
                    networking investigation to ground assumptions about the network plane.
                    """)
    public String inspectClusterNetworkInfrastructure() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== CLUSTER NETWORK INFRASTRUCTURE ===\n\n");

        // CNI detection via DaemonSets across all namespaces.
        sb.append("[CNI detection]\n");
        List<DaemonSet> dsList =
                kubernetesClient.apps().daemonSets().inAnyNamespace().list().getItems();
        boolean foundCni = false;
        for (DaemonSet ds : dsList) {
            String name = ds.getMetadata().getName().toLowerCase();
            String ns = ds.getMetadata().getNamespace();
            String detected = matchCniName(name, ns);
            if (detected != null) {
                Integer desired = Optional.ofNullable(ds.getStatus())
                        .map(s -> s.getDesiredNumberScheduled())
                        .orElse(0);
                Integer ready = Optional.ofNullable(ds.getStatus())
                        .map(s -> s.getNumberReady())
                        .orElse(0);
                sb.append("  ")
                        .append(detected)
                        .append("  daemonset=")
                        .append(ns)
                        .append("/")
                        .append(ds.getMetadata().getName())
                        .append("  desired=")
                        .append(desired)
                        .append("  ready=")
                        .append(ready);
                if (!desired.equals(ready)) sb.append("  ⚠ DEGRADED");
                sb.append("\n");
                foundCni = true;
            }
        }
        if (!foundCni) {
            sb.append("  no recognised CNI DaemonSet detected. The cluster may use a non-DaemonSet\n");
            sb.append("  CNI (e.g. managed cloud CNI) or one with unusual naming.\n");
        }
        sb.append("\n");

        // kube-proxy
        sb.append("[kube-proxy]\n");
        DaemonSet kp = kubernetesClient
                .apps()
                .daemonSets()
                .inNamespace("kube-system")
                .withName("kube-proxy")
                .get();
        if (kp == null) {
            sb.append(
                    "  no kube-proxy DaemonSet (cluster may use proxy-less CNI like Cilium with kubeProxyReplacement).\n");
        } else {
            Integer desired = Optional.ofNullable(kp.getStatus())
                    .map(s -> s.getDesiredNumberScheduled())
                    .orElse(0);
            Integer ready = Optional.ofNullable(kp.getStatus())
                    .map(s -> s.getNumberReady())
                    .orElse(0);
            sb.append("  daemonset=kube-system/kube-proxy  desired=")
                    .append(desired)
                    .append("  ready=")
                    .append(ready);
            if (!desired.equals(ready)) sb.append("  ⚠ DEGRADED");
            sb.append("\n");
        }
        sb.append("\n");

        // LoadBalancer provider hints
        sb.append("[LoadBalancer provider hints]\n");
        boolean foundLb = false;
        for (DaemonSet ds : dsList) {
            String name = ds.getMetadata().getName().toLowerCase();
            if (name.contains("metallb") || name.contains("speaker")) {
                sb.append("  metallb detected: ")
                        .append(ds.getMetadata().getNamespace())
                        .append("/")
                        .append(ds.getMetadata().getName())
                        .append("\n");
                foundLb = true;
            }
        }
        List<Deployment> deployments =
                kubernetesClient.apps().deployments().inAnyNamespace().list().getItems();
        for (Deployment d : deployments) {
            String name = d.getMetadata().getName().toLowerCase();
            if (name.contains("metallb-controller")) {
                sb.append("  metallb-controller: ")
                        .append(d.getMetadata().getNamespace())
                        .append("/")
                        .append(d.getMetadata().getName())
                        .append("\n");
                foundLb = true;
            }
            if (name.contains("cloud-provider") || name.contains("cloud-controller")) {
                sb.append("  cloud LB controller: ")
                        .append(d.getMetadata().getNamespace())
                        .append("/")
                        .append(d.getMetadata().getName())
                        .append("\n");
                foundLb = true;
            }
        }
        if (!foundLb) {
            sb.append("  none detected. Services of type=LoadBalancer will stay <pending> unless an\n");
            sb.append("  external LB controller (metallb, cloud-provider, cilium LB) is installed.\n");
        }
        sb.append("\n");

        // Per-node addressing
        sb.append("[Node addressing]\n");
        List<Node> nodes = kubernetesClient.nodes().list().getItems();
        for (Node n : nodes) {
            String podCidr =
                    Optional.ofNullable(n.getSpec()).map(NodeSpec::getPodCIDR).orElse(null);
            List<String> podCidrs =
                    Optional.ofNullable(n.getSpec()).map(NodeSpec::getPodCIDRs).orElse(Collections.emptyList());
            String internalIp =
                    Optional.ofNullable(n.getStatus())
                            .map(NodeStatus::getAddresses)
                            .orElse(Collections.emptyList())
                            .stream()
                            .filter(a -> "InternalIP".equals(a.getType()))
                            .map(NodeAddress::getAddress)
                            .findFirst()
                            .orElse("<none>");
            sb.append("  ")
                    .append(n.getMetadata().getName())
                    .append("  internalIP=")
                    .append(internalIp)
                    .append("  podCIDR=")
                    .append(safe(podCidr));
            if (podCidrs.size() > 1) sb.append("  podCIDRs=").append(podCidrs);
            sb.append("\n");
        }

        sb.append("\n[NOTES]\n");
        sb.append("  - If multiple CNIs are listed, only one should be the active dataplane; the rest\n");
        sb.append("    are likely sidecars (e.g. multus) or stale installs.\n");
        sb.append("  - PodCIDR collisions across nodes (or with the Service CIDR) will silently break\n");
        sb.append("    pod-to-pod traffic. Compare against your kube-controller-manager --cluster-cidr.\n");
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers — service / endpoints
    // ──────────────────────────────────────────────────────────────────────────

    private void appendServiceEvidence(StringBuilder sb, Service svc) {
        ServiceSpec spec = svc.getSpec();
        sb.append("  type:               ")
                .append(safe(spec == null ? null : spec.getType()))
                .append("\n");
        sb.append("  clusterIP:          ")
                .append(safe(spec == null ? null : spec.getClusterIP()))
                .append("\n");
        if (spec != null && spec.getClusterIPs() != null && spec.getClusterIPs().size() > 1) {
            sb.append("  clusterIPs:         ").append(spec.getClusterIPs()).append("\n");
        }
        if (spec != null
                && spec.getExternalIPs() != null
                && !spec.getExternalIPs().isEmpty()) {
            sb.append("  externalIPs:        ").append(spec.getExternalIPs()).append("\n");
        }
        if (spec != null && spec.getExternalName() != null) {
            sb.append("  externalName:       ").append(spec.getExternalName()).append("\n");
        }
        sb.append("  sessionAffinity:    ")
                .append(safe(spec == null ? null : spec.getSessionAffinity()))
                .append("\n");
        if (spec != null && spec.getExternalTrafficPolicy() != null) {
            sb.append("  externalTrafficPolicy: ")
                    .append(spec.getExternalTrafficPolicy())
                    .append("\n");
        }
        if (spec != null && spec.getInternalTrafficPolicy() != null) {
            sb.append("  internalTrafficPolicy: ")
                    .append(spec.getInternalTrafficPolicy())
                    .append("\n");
        }
        Map<String, String> sel = spec == null
                ? Collections.emptyMap()
                : Optional.ofNullable(spec.getSelector()).orElse(Collections.emptyMap());
        sb.append("  selector:           ")
                .append(sel.isEmpty() ? "<NONE>" : sel.toString())
                .append("\n");

        List<ServicePort> ports = spec == null
                ? Collections.emptyList()
                : Optional.ofNullable(spec.getPorts()).orElse(Collections.emptyList());
        sb.append("  ports:\n");
        for (ServicePort p : ports) {
            sb.append("    - name=")
                    .append(safe(p.getName()))
                    .append("  port=")
                    .append(p.getPort())
                    .append("  targetPort=")
                    .append(
                            p.getTargetPort() == null
                                    ? "<none>"
                                    : p.getTargetPort().getValue())
                    .append("  protocol=")
                    .append(safe(p.getProtocol()));
            if (p.getNodePort() != null) sb.append("  nodePort=").append(p.getNodePort());
            sb.append("\n");
        }

        if ("LoadBalancer"
                .equals(Optional.ofNullable(spec).map(ServiceSpec::getType).orElse(""))) {
            LoadBalancerStatus lbs = Optional.ofNullable(svc.getStatus())
                    .map(ServiceStatus::getLoadBalancer)
                    .orElse(null);
            List<LoadBalancerIngress> lbIng = lbs == null
                    ? Collections.emptyList()
                    : Optional.ofNullable(lbs.getIngress()).orElse(Collections.emptyList());
            if (lbIng.isEmpty()) {
                sb.append("  loadBalancer.ingress: (empty)  ⚠ EXTERNAL IP PENDING\n");
            } else {
                sb.append("  loadBalancer.ingress:\n");
                for (LoadBalancerIngress li : lbIng) {
                    sb.append("    ip=")
                            .append(safe(li.getIp()))
                            .append("  hostname=")
                            .append(safe(li.getHostname()))
                            .append("\n");
                }
            }
        }
        sb.append("\n");
    }

    private void appendEndpointsEvidence(StringBuilder sb, Endpoints endpoints) {
        if (endpoints == null) {
            sb.append("[Endpoints] none (controller has not created an Endpoints object)\n\n");
            return;
        }
        long ready = countReadyEndpoints(endpoints);
        long notReady = countNotReadyEndpoints(endpoints);
        sb.append("[Endpoints] subsets=")
                .append(Optional.ofNullable(endpoints.getSubsets())
                        .orElse(Collections.emptyList())
                        .size())
                .append("  readyAddresses=")
                .append(ready)
                .append("  notReadyAddresses=")
                .append(notReady);
        if (ready == 0) sb.append("  ⚠ NO READY BACKENDS");
        sb.append("\n\n");
    }

    private void appendEndpointSlicesEvidence(StringBuilder sb, List<EndpointSlice> slices) {
        if (slices.isEmpty()) {
            sb.append("[EndpointSlices] none\n\n");
            return;
        }
        long totalEndpoints = slices.stream()
                .flatMap(s -> Optional.ofNullable(s.getEndpoints()).orElse(Collections.emptyList()).stream())
                .count();
        long readyEndpoints = slices.stream()
                .flatMap(s -> Optional.ofNullable(s.getEndpoints()).orElse(Collections.emptyList()).stream())
                .filter(e -> e.getConditions() != null
                        && Boolean.TRUE.equals(e.getConditions().getReady()))
                .count();
        sb.append("[EndpointSlices] slices=")
                .append(slices.size())
                .append("  totalEndpoints=")
                .append(totalEndpoints)
                .append("  readyEndpoints=")
                .append(readyEndpoints)
                .append("\n\n");
    }

    private void appendMatchedPodsEvidence(StringBuilder sb, Map<String, String> selector, List<Pod> pods) {
        if (selector.isEmpty()) {
            sb.append(
                    "[Matched pods] (service has no selector — endpoints must be managed manually or it's an ExternalName)\n\n");
            return;
        }
        sb.append("[Matched pods] count=").append(pods.size()).append("\n");
        int shown = 0;
        for (Pod p : pods) {
            if (shown >= MAX_LIST_PREVIEW) {
                sb.append("  ... (").append(pods.size() - shown).append(" more)\n");
                break;
            }
            String phase =
                    Optional.ofNullable(p.getStatus()).map(PodStatus::getPhase).orElse("?");
            boolean ready = isPodReady(p);
            String podIP =
                    Optional.ofNullable(p.getStatus()).map(PodStatus::getPodIP).orElse("<none>");
            sb.append("  ")
                    .append(p.getMetadata().getName())
                    .append("  phase=")
                    .append(phase)
                    .append("  ready=")
                    .append(ready)
                    .append("  ip=")
                    .append(podIP)
                    .append("\n");
            shown++;
        }
        sb.append("\n");
    }

    private void appendEndpointAddresses(StringBuilder sb, String label, List<EndpointAddress> addrs) {
        if (addrs == null || addrs.isEmpty()) {
            sb.append(label).append(": (none)\n");
            return;
        }
        sb.append(label).append(": (").append(addrs.size()).append(")\n");
        int shown = 0;
        for (EndpointAddress a : addrs) {
            if (shown++ >= MAX_LIST_PREVIEW) {
                sb.append("      ... (").append(addrs.size() - MAX_LIST_PREVIEW).append(" more)\n");
                break;
            }
            String target = a.getTargetRef() == null
                    ? "-"
                    : a.getTargetRef().getKind() + "/" + a.getTargetRef().getName();
            sb.append("      ip=")
                    .append(safe(a.getIp()))
                    .append("  node=")
                    .append(safe(a.getNodeName()))
                    .append("  target=")
                    .append(target)
                    .append("\n");
        }
    }

    private void appendServiceSuspicions(
            StringBuilder sb, Service svc, Endpoints endpoints, List<EndpointSlice> slices, List<Pod> matchedPods) {
        sb.append("\n[SUSPICIONS]\n");
        ServiceSpec spec = svc.getSpec();
        Map<String, String> sel = spec == null
                ? Collections.emptyMap()
                : Optional.ofNullable(spec.getSelector()).orElse(Collections.emptyMap());
        String type = spec == null ? "" : Optional.ofNullable(spec.getType()).orElse("");

        boolean any = false;
        long ready = countReadyEndpoints(endpoints);
        long notReady = countNotReadyEndpoints(endpoints);

        if (sel.isEmpty() && !"ExternalName".equals(type)) {
            sb.append("  - Service has no selector and is not ExternalName. Endpoints must be created\n");
            sb.append("    manually. Traffic will go nowhere unless you maintain Endpoints yourself.\n");
            any = true;
        }
        if (!sel.isEmpty() && matchedPods.isEmpty()) {
            sb.append("  - Selector ").append(sel).append(" matches NO pods. Check pod labels for typos\n");
            sb.append("    or update the selector to match an existing label set.\n");
            any = true;
        }
        if (!matchedPods.isEmpty() && ready == 0 && notReady == 0) {
            sb.append("  - Selector matches pods but no Endpoints addresses exist. The endpoint controller\n");
            sb.append("    may be lagging — wait a moment and re-check, or restart kube-controller-manager.\n");
            any = true;
        }
        if (ready == 0 && notReady > 0) {
            sb.append("  - Endpoints exist but all are NotReady (")
                    .append(notReady)
                    .append("). Readiness probes are failing.\n");
            sb.append("    Run inspectPodNetworking on a backing pod to examine probe definitions.\n");
            any = true;
        }
        if (matchedPods.stream().anyMatch(p -> {
            Map<String, String> labels =
                    Optional.ofNullable(p.getMetadata().getLabels()).orElse(Collections.emptyMap());
            return sel.entrySet().stream().anyMatch(e -> !e.getValue().equals(labels.get(e.getKey())));
        })) {
            sb.append("  - At least one matched pod's labels differ from the service selector (case-sensitive).\n");
            any = true;
        }
        if (!matchedPods.isEmpty() && spec != null) {
            for (ServicePort sp : Optional.ofNullable(spec.getPorts()).orElse(Collections.emptyList())) {
                if (sp.getTargetPort() == null) continue;
                String tpName = sp.getTargetPort().getStrVal();
                if (tpName != null && !tpName.isBlank()) {
                    boolean anyHas = matchedPods.stream()
                            .anyMatch(p -> Optional.ofNullable(p.getSpec())
                                    .map(PodSpec::getContainers)
                                    .orElse(Collections.emptyList())
                                    .stream()
                                    .flatMap(c ->
                                            Optional.ofNullable(c.getPorts()).orElse(Collections.emptyList()).stream())
                                    .anyMatch(cp -> tpName.equals(cp.getName())));
                    if (!anyHas) {
                        sb.append("  - Service port '")
                                .append(safe(sp.getName()))
                                .append("' targets named port '")
                                .append(tpName)
                                .append("' but no matched pod declares a containerPort with that name.\n");
                        any = true;
                    }
                }
            }
        }
        if ("LoadBalancer".equals(type)) {
            LoadBalancerStatus lbs = Optional.ofNullable(svc.getStatus())
                    .map(ServiceStatus::getLoadBalancer)
                    .orElse(null);
            List<LoadBalancerIngress> lbIng = lbs == null
                    ? Collections.emptyList()
                    : Optional.ofNullable(lbs.getIngress()).orElse(Collections.emptyList());
            if (lbIng.isEmpty()) {
                sb.append("  - LoadBalancer has no ingress address. No LB controller (metallb / cloud-provider /\n");
                sb.append("    cilium LB) has assigned an IP. Run inspectClusterNetworkInfrastructure.\n");
                any = true;
            }
        }
        if (!any) {
            sb.append("  - Service config + endpoints look healthy. If the client still cannot connect,\n");
            sb.append("    check NetworkPolicies in this namespace and the client's namespace,\n");
            sb.append("    CNI/kube-proxy health, and whether the client uses the right DNS suffix.\n");
        }
    }

    private void appendServiceTable(StringBuilder sb, List<Service> services) {
        sb.append(String.format(
                "%-25s  %-40s  %-14s  %-15s  %-30s  %-30s  %s%n",
                "NAMESPACE", "NAME", "TYPE", "CLUSTER-IP", "PORTS", "SELECTOR", "READY-EPS"));
        sb.append("-".repeat(170)).append("\n");
        for (Service s : services) {
            ServiceSpec spec = s.getSpec();
            String type =
                    spec == null ? "?" : Optional.ofNullable(spec.getType()).orElse("?");
            String clusterIP = spec == null ? "?" : safe(spec.getClusterIP());
            String ports = spec == null ? "" : formatServicePorts(spec.getPorts());
            String selector = spec == null
                            || spec.getSelector() == null
                            || spec.getSelector().isEmpty()
                    ? "<none>"
                    : spec.getSelector().entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .collect(Collectors.joining(","));
            long readyEps;
            try {
                Endpoints eps = kubernetesClient
                        .endpoints()
                        .inNamespace(s.getMetadata().getNamespace())
                        .withName(s.getMetadata().getName())
                        .get();
                readyEps = countReadyEndpoints(eps);
            } catch (KubernetesClientException e) {
                readyEps = -1;
            }
            sb.append(String.format(
                    "%-25s  %-40s  %-14s  %-15s  %-30s  %-30s  %s%n",
                    truncate(s.getMetadata().getNamespace(), 25),
                    truncate(s.getMetadata().getName(), 40),
                    type,
                    truncate(clusterIP, 15),
                    truncate(ports, 30),
                    truncate(selector, 30),
                    readyEps < 0 ? "?" : String.valueOf(readyEps)));
        }
    }

    private String serviceNotFoundHint(String namespace, String serviceName) {
        StringBuilder sb = new StringBuilder();
        sb.append("ERROR: service ")
                .append(namespace)
                .append("/")
                .append(serviceName)
                .append(" not found.\n\n");

        // Cheap fuzzy hint: services with a similar name across the cluster.
        String needle = serviceName == null ? "" : serviceName.toLowerCase();
        List<String> candidates = Collections.emptyList();
        if (!needle.isBlank()) {
            try {
                candidates = kubernetesClient.services().inAnyNamespace().list().getItems().stream()
                        .filter(s -> {
                            String n = s.getMetadata().getName().toLowerCase();
                            return tokenOverlap(n, needle);
                        })
                        .map(s -> s.getMetadata().getNamespace() + "/"
                                + s.getMetadata().getName())
                        .sorted()
                        .limit(10)
                        .toList();
            } catch (KubernetesClientException e) {
                log.debug("Could not enumerate services for fuzzy hint", e);
            }
        }

        if (!candidates.isEmpty()) {
            sb.append("Did you mean one of these?\n");
            for (String c : candidates) sb.append("  - ").append(c).append("\n");
            sb.append("\n");
        }
        sb.append("Next steps:\n");
        sb.append("  - Call findServices(\"")
                .append(needle.isBlank() ? "<keyword>" : needle)
                .append("\") to search by substring across all namespaces.\n");
        sb.append("  - Call listServices(\"")
                .append(safe(namespace))
                .append("\") to see every Service in that namespace.\n");
        sb.append("  - Call listNamespaces() if you're not sure the namespace is correct.\n");
        return sb.toString();
    }

    /**
     * Returns true if name shares any non-trivial token (length >= 3) with needle, or contains it.
     * Used purely as a "did you mean" hint so the LLM stops guessing.
     */
    private boolean tokenOverlap(String name, String needle) {
        if (name.contains(needle) || needle.contains(name)) return true;
        Set<String> nameTokens = new HashSet<>();
        for (String t : name.split("[-_.]")) if (t.length() >= 3) nameTokens.add(t);
        for (String t : needle.split("[-_.]")) if (t.length() >= 3 && nameTokens.contains(t)) return true;
        return false;
    }

    private List<EndpointSlice> listEndpointSlicesForService(String namespace, String serviceName) {
        try {
            return kubernetesClient
                    .discovery()
                    .v1()
                    .endpointSlices()
                    .inNamespace(namespace)
                    .withLabel("kubernetes.io/service-name", serviceName)
                    .list()
                    .getItems();
        } catch (KubernetesClientException e) {
            log.debug("EndpointSlices unavailable for {}/{}: {}", namespace, serviceName, e.getMessage());
            return Collections.emptyList();
        }
    }

    private long countReadyEndpoints(Endpoints endpoints) {
        if (endpoints == null) return 0;
        return Optional.ofNullable(endpoints.getSubsets()).orElse(Collections.emptyList()).stream()
                .flatMap(s -> Optional.ofNullable(s.getAddresses()).orElse(Collections.emptyList()).stream())
                .count();
    }

    private long countNotReadyEndpoints(Endpoints endpoints) {
        if (endpoints == null) return 0;
        return Optional.ofNullable(endpoints.getSubsets()).orElse(Collections.emptyList()).stream()
                .flatMap(s -> Optional.ofNullable(s.getNotReadyAddresses()).orElse(Collections.emptyList()).stream())
                .count();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers — ingress
    // ──────────────────────────────────────────────────────────────────────────

    private String formatIngressBackend(IngressBackend b) {
        if (b == null) return "<none>";
        if (b.getService() != null) {
            IngressServiceBackend isb = b.getService();
            return "Service " + safe(isb.getName()) + ":" + formatServiceBackendPort(isb.getPort());
        }
        if (b.getResource() != null) {
            TypedLocalObjectReference r = b.getResource();
            return "Resource " + safe(r.getApiGroup()) + "/" + safe(r.getKind()) + "/" + safe(r.getName());
        }
        return "<empty>";
    }

    private String formatServiceBackendPort(ServiceBackendPort p) {
        if (p == null) return "<none>";
        if (p.getNumber() != null) return String.valueOf(p.getNumber());
        if (p.getName() != null) return p.getName();
        return "<none>";
    }

    private boolean servicePortMatches(Service svc, ServiceBackendPort p) {
        if (svc == null || svc.getSpec() == null || p == null) return false;
        List<ServicePort> ports = Optional.ofNullable(svc.getSpec().getPorts()).orElse(Collections.emptyList());
        if (p.getNumber() != null) {
            return ports.stream().anyMatch(sp -> p.getNumber().equals(sp.getPort()));
        }
        if (p.getName() != null) {
            return ports.stream().anyMatch(sp -> p.getName().equals(sp.getName()));
        }
        return false;
    }

    private boolean secretExists(String namespace, String name) {
        if (name == null || name.isBlank()) return false;
        try {
            return kubernetesClient
                            .secrets()
                            .inNamespace(namespace)
                            .withName(name)
                            .get()
                    != null;
        } catch (KubernetesClientException e) {
            return false;
        }
    }

    private boolean ingressClassExists(String name) {
        try {
            return kubernetesClient
                            .network()
                            .v1()
                            .ingressClasses()
                            .withName(name)
                            .get()
                    != null;
        } catch (KubernetesClientException e) {
            return false;
        }
    }

    private boolean hasDefaultIngressClass() {
        try {
            return kubernetesClient.network().v1().ingressClasses().list().getItems().stream()
                    .anyMatch(ic -> "true"
                            .equals(Optional.ofNullable(ic.getMetadata().getAnnotations())
                                    .map(a -> a.get("ingressclass.kubernetes.io/is-default-class"))
                                    .orElse("false")));
        } catch (KubernetesClientException e) {
            return false;
        }
    }

    private String detectIngressController(Pod p) {
        Map<String, String> labels =
                Optional.ofNullable(p.getMetadata().getLabels()).orElse(Collections.emptyMap());
        for (String key : KNOWN_CNI_LABELS) {
            String v = labels.get(key);
            if (v == null) continue;
            String lc = v.toLowerCase();
            if (lc.contains("ingress-nginx") || lc.equals("nginx-ingress")) return "ingress-nginx";
            if (lc.contains("traefik")) return "traefik";
            if (lc.contains("haproxy")) return "haproxy-ingress";
            if (lc.contains("contour")) return "contour";
            if (lc.contains("kong")) return "kong";
            if (lc.contains("istio-ingress") || lc.contains("istio-gateway")) return "istio";
            if (lc.contains("gloo")) return "gloo";
        }
        return null;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers — NetworkPolicy
    // ──────────────────────────────────────────────────────────────────────────

    private boolean labelSelectorMatches(LabelSelector selector, Map<String, String> labels) {
        if (selector == null) return false;
        Map<String, String> match =
                Optional.ofNullable(selector.getMatchLabels()).orElse(Collections.emptyMap());
        List<LabelSelectorRequirement> exprs =
                Optional.ofNullable(selector.getMatchExpressions()).orElse(Collections.emptyList());
        if (match.isEmpty() && exprs.isEmpty()) return true; // empty selector matches everything

        for (Map.Entry<String, String> e : match.entrySet()) {
            if (!e.getValue().equals(labels.get(e.getKey()))) return false;
        }
        for (LabelSelectorRequirement req : exprs) {
            String v = labels.get(req.getKey());
            List<String> vals = Optional.ofNullable(req.getValues()).orElse(Collections.emptyList());
            switch (Optional.ofNullable(req.getOperator()).orElse("")) {
                case "In" -> {
                    if (v == null || !vals.contains(v)) return false;
                }
                case "NotIn" -> {
                    if (v != null && vals.contains(v)) return false;
                }
                case "Exists" -> {
                    if (!labels.containsKey(req.getKey())) return false;
                }
                case "DoesNotExist" -> {
                    if (labels.containsKey(req.getKey())) return false;
                }
                default -> {
                    /* unknown operator; conservatively fail-open */
                }
            }
        }
        return true;
    }

    private String formatLabelSelector(LabelSelector sel) {
        Map<String, String> m = Optional.ofNullable(sel.getMatchLabels()).orElse(Collections.emptyMap());
        List<LabelSelectorRequirement> exprs =
                Optional.ofNullable(sel.getMatchExpressions()).orElse(Collections.emptyList());
        if (m.isEmpty() && exprs.isEmpty()) return "{} (ALL pods)";
        StringBuilder sb = new StringBuilder();
        sb.append(m.toString());
        if (!exprs.isEmpty()) {
            sb.append("+exprs=")
                    .append(exprs.stream()
                            .map(e -> e.getKey() + " " + e.getOperator() + " "
                                    + Optional.ofNullable(e.getValues()).orElse(Collections.emptyList()))
                            .collect(Collectors.joining(",")));
        }
        return sb.toString();
    }

    private String formatPeers(List<NetworkPolicyPeer> peers) {
        if (peers == null || peers.isEmpty()) return "ALL (rule has no 'from'/'to' restriction)";
        return peers.stream()
                .map(p -> {
                    StringBuilder s = new StringBuilder();
                    if (p.getPodSelector() != null) s.append("pod=").append(formatLabelSelector(p.getPodSelector()));
                    if (p.getNamespaceSelector() != null) {
                        if (s.length() > 0) s.append(",");
                        s.append("ns=").append(formatLabelSelector(p.getNamespaceSelector()));
                    }
                    if (p.getIpBlock() != null) {
                        if (s.length() > 0) s.append(",");
                        s.append("ipBlock=").append(p.getIpBlock().getCidr());
                        if (p.getIpBlock().getExcept() != null
                                && !p.getIpBlock().getExcept().isEmpty()) {
                            s.append(" except=").append(p.getIpBlock().getExcept());
                        }
                    }
                    return "{" + s + "}";
                })
                .collect(Collectors.joining(", "));
    }

    private String formatNpPorts(List<NetworkPolicyPort> ports) {
        if (ports == null || ports.isEmpty()) return "ALL ports";
        return ports.stream()
                .map(p -> {
                    String proto = Optional.ofNullable(p.getProtocol()).orElse("TCP");
                    String port =
                            p.getPort() == null ? "*" : p.getPort().getValue().toString();
                    String endPort = p.getEndPort() == null ? "" : "-" + p.getEndPort();
                    return proto + ":" + port + endPort;
                })
                .collect(Collectors.joining(", "));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers — CNI detection
    // ──────────────────────────────────────────────────────────────────────────

    private String matchCniName(String name, String namespace) {
        if (name.contains("calico-node")) return "calico";
        if (name.contains("cilium")) return "cilium";
        if (name.contains("kube-flannel") || name.equals("flannel")) return "flannel";
        if (name.contains("weave")) return "weave";
        if (name.contains("kube-router")) return "kube-router";
        if (name.contains("antrea")) return "antrea";
        if (name.contains("multus")) return "multus (meta-CNI)";
        if (name.contains("aws-node")) return "aws-vpc-cni";
        if (name.contains("azure-cni")) return "azure-cni";
        if ("kube-system".equals(namespace) && name.contains("cni")) return "cni-plugin(" + name + ")";
        return null;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers — generic
    // ──────────────────────────────────────────────────────────────────────────

    private String formatProbe(Probe probe) {
        if (probe == null) return "<none>";
        StringBuilder sb = new StringBuilder();
        if (probe.getHttpGet() != null) {
            HTTPGetAction h = probe.getHttpGet();
            sb.append("HTTP ")
                    .append(safe(h.getScheme()))
                    .append(" ")
                    .append(safe(h.getPath()))
                    .append(" port=")
                    .append(h.getPort() == null ? "?" : h.getPort().getValue());
        } else if (probe.getTcpSocket() != null) {
            TCPSocketAction t = probe.getTcpSocket();
            sb.append("TCP port=")
                    .append(t.getPort() == null ? "?" : t.getPort().getValue());
        } else if (probe.getExec() != null) {
            sb.append("Exec ")
                    .append(Optional.ofNullable(probe.getExec().getCommand()).orElse(Collections.emptyList()));
        } else if (probe.getGrpc() != null) {
            sb.append("gRPC port=").append(probe.getGrpc().getPort());
        } else {
            sb.append("<unknown handler>");
        }
        if (probe.getInitialDelaySeconds() != null)
            sb.append(" initialDelay=").append(probe.getInitialDelaySeconds()).append("s");
        if (probe.getPeriodSeconds() != null)
            sb.append(" period=").append(probe.getPeriodSeconds()).append("s");
        if (probe.getTimeoutSeconds() != null)
            sb.append(" timeout=").append(probe.getTimeoutSeconds()).append("s");
        if (probe.getFailureThreshold() != null) sb.append(" failureThreshold=").append(probe.getFailureThreshold());
        return sb.toString();
    }

    private String formatServicePorts(List<ServicePort> ports) {
        if (ports == null || ports.isEmpty()) return "<none>";
        return ports.stream()
                .map(p -> safe(p.getName()) + ":" + p.getPort() + "/" + safe(p.getProtocol()))
                .collect(Collectors.joining(", "));
    }

    private List<Event> fetchObjectEvents(String namespace, String name) {
        try {
            return kubernetesClient
                    .v1()
                    .events()
                    .inNamespace(namespace)
                    .withField("involvedObject.name", name)
                    .list()
                    .getItems()
                    .stream()
                    .sorted(Comparator.comparing(
                            e -> Optional.ofNullable(e.getLastTimestamp()).orElse(""), Comparator.reverseOrder()))
                    .limit(MAX_EVENTS)
                    .toList();
        } catch (Exception e) {
            log.debug("Could not fetch events for {}/{}: {}", namespace, name, e.getMessage());
            return Collections.emptyList();
        }
    }

    private void appendEvents(StringBuilder sb, List<Event> events, String header) {
        if (events.isEmpty()) {
            sb.append("\n[").append(header).append("] none\n");
            return;
        }
        sb.append("\n[")
                .append(header)
                .append("] (most recent first, max ")
                .append(MAX_EVENTS)
                .append(")\n");
        for (Event e : events) {
            sb.append("  [")
                    .append(e.getType())
                    .append("] ")
                    .append(e.getReason())
                    .append(": ")
                    .append(e.getMessage())
                    .append("\n");
        }
    }

    private boolean isPodReady(Pod pod) {
        return Optional.ofNullable(pod.getStatus())
                .map(PodStatus::getConditions)
                .orElse(Collections.emptyList())
                .stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
    }

    private String safe(String s) {
        return s == null || s.isBlank() ? "<none>" : s;
    }

    private String boolStr(Boolean b) {
        return b == null ? "?" : b.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}

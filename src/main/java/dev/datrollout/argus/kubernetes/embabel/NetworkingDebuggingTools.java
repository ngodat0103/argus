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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
                    error, or shows a pending external address. Returns ingressClassName, all rules
                    (host/path/pathType/backend), TLS sections with secret existence, LoadBalancer
                    status, backend Service existence + endpoint counts, and a SUSPICIONS block for
                    missing backends, missing TLS secrets, wrong path types, or unbound LB.
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
        for (IngressTLS t : tls) {
            if (t.getSecretName() != null && !secretExists(namespace, t.getSecretName())) {
                sb.append("  - TLS secret '")
                        .append(t.getSecretName())
                        .append("' is referenced but missing — handshake will fail.\n");
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

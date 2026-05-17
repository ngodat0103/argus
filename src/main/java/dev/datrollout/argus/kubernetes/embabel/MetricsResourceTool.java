package dev.datrollout.argus.kubernetes.embabel;

import com.embabel.agent.api.annotation.LlmTool;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.ContainerMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.NodeMetrics;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
@Component
public class MetricsResourceTool {

    private static final int MAX_EVENTS = 10;

    private final KubernetesClient kubernetesClient;

    // ──────────────────────────────────────────────────────────────────────────
    // Public LLM tools
    // ──────────────────────────────────────────────────────────────────────────

    @LlmTool(
            name = "inspectPodResourceHealth",
            description = """
                    Use this tool when a specific pod is OOMKilled, in CrashLoopBackOff, throttled,
                    stuck in Pending, or when you need to audit a pod's resource requests/limits.
                    Returns pod phase, QoS class, per-container requests/limits, restart counts,
                    last termination reason (OOMKilled / exit code 137), and recent pod events.
                    Ends with a SUSPICIONS block listing likely root causes.
                    """
    )
    public String inspectPodResourceHealth(String namespace, String podName) {
        Pod pod = kubernetesClient.pods().inNamespace(namespace).withName(podName).get();
        if (pod == null) {
            return "ERROR: pod " + namespace + "/" + podName + " not found.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== POD RESOURCE HEALTH: ").append(namespace).append("/").append(podName).append(" ===\n\n");

        appendPodEvidence(sb, pod);

        List<Event> events = fetchPodEvents(namespace, podName);
        appendEvents(sb, events);

        appendPodSuspicions(sb, pod);
        return sb.toString();
    }

    @LlmTool(
            name = "inspectWorkloadResourceHealth",
            description = """
                    Use this tool when troubleshooting a Deployment, StatefulSet, DaemonSet, ReplicaSet,
                    Job, or CronJob that has OOMKilled pods, restarts, pending pods, or resource quota
                    admission failures. Provide kind as one of: Deployment, StatefulSet, DaemonSet,
                    ReplicaSet, Job, CronJob. Returns per-pod resource health and a pattern summary
                    across the workload's pod fleet.
                    """
    )
    public String inspectWorkloadResourceHealth(String namespace, String kind, String name) {
        Map<String, String> selector = resolveSelector(namespace, kind, name);
        if (selector == null) {
            return "ERROR: could not find " + kind + " " + namespace + "/" + name
                    + " or selector resolution is unsupported for kind " + kind + ".";
        }

        List<Pod> pods = kubernetesClient.pods()
                .inNamespace(namespace)
                .withLabels(selector)
                .list()
                .getItems();

        if (pods.isEmpty()) {
            return "No pods found for " + kind + " " + namespace + "/" + name
                    + " with selector " + selector + ".";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== WORKLOAD RESOURCE HEALTH: ").append(kind).append(" ").append(namespace)
                .append("/").append(name).append(" ===\n");
        sb.append("Pod count: ").append(pods.size()).append("\n\n");

        int oomCount = 0;
        int restartTotal = 0;
        int missingLimits = 0;
        int missingRequests = 0;
        int bestEffortCount = 0;

        for (Pod pod : pods) {
            sb.append("--- Pod: ").append(pod.getMetadata().getName()).append(" ---\n");
            appendPodEvidence(sb, pod);
            sb.append("\n");

            if (hasPodOOMKilled(pod)) oomCount++;
            restartTotal += totalRestarts(pod);
            if (hasMissingLimits(pod)) missingLimits++;
            if (hasMissingRequests(pod)) missingRequests++;
            if ("BestEffort".equals(qosClass(pod))) bestEffortCount++;
        }

        sb.append("=== WORKLOAD PATTERN SUMMARY ===\n");
        sb.append("OOMKilled pods: ").append(oomCount).append("/").append(pods.size()).append("\n");
        sb.append("Total restarts across fleet: ").append(restartTotal).append("\n");
        sb.append("Pods missing memory/cpu limits: ").append(missingLimits).append("/").append(pods.size()).append("\n");
        sb.append("Pods missing memory/cpu requests: ").append(missingRequests).append("/").append(pods.size()).append("\n");
        sb.append("BestEffort QoS pods: ").append(bestEffortCount).append("/").append(pods.size()).append("\n");
        return sb.toString();
    }

    @LlmTool(
            name = "inspectNamespaceResourceConstraints",
            description = """
                    Use this tool when pods fail admission with "exceeded quota", "must specify limits",
                    or "minimum cpu/memory" errors, or when you suspect a namespace is at quota capacity.
                    Returns ResourceQuota status (used vs hard limits) and LimitRange defaults/bounds
                    so the LLM can identify quota exhaustion, missing default limits, and admission blockers.
                    """
    )
    public String inspectNamespaceResourceConstraints(String namespace) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== NAMESPACE RESOURCE CONSTRAINTS: ").append(namespace).append(" ===\n\n");

        List<ResourceQuota> quotas = kubernetesClient.resourceQuotas()
                .inNamespace(namespace).list().getItems();
        if (quotas.isEmpty()) {
            sb.append("[ResourceQuotas] none defined\n");
        } else {
            sb.append("[ResourceQuotas]\n");
            for (ResourceQuota q : quotas) {
                sb.append("  ").append(q.getMetadata().getName()).append(":\n");
                Map<String, Quantity> hard = Optional.ofNullable(q.getStatus())
                        .map(ResourceQuotaStatus::getHard).orElse(Collections.emptyMap());
                Map<String, Quantity> used = Optional.ofNullable(q.getStatus())
                        .map(ResourceQuotaStatus::getUsed).orElse(Collections.emptyMap());
                Set<String> keys = new TreeSet<>(hard.keySet());
                for (String key : keys) {
                    String hardVal = quantityStr(hard.get(key));
                    String usedVal = quantityStr(used.get(key));
                    boolean nearLimit = isNearLimit(used.get(key), hard.get(key));
                    sb.append("    ").append(key)
                            .append(": used=").append(usedVal)
                            .append(" hard=").append(hardVal);
                    if (nearLimit) sb.append("  ⚠ NEAR LIMIT");
                    sb.append("\n");
                }
            }
        }

        sb.append("\n");

        List<LimitRange> limitRanges = kubernetesClient.limitRanges()
                .inNamespace(namespace).list().getItems();
        if (limitRanges.isEmpty()) {
            sb.append("[LimitRanges] none defined — no default requests/limits injected into pods\n");
        } else {
            sb.append("[LimitRanges]\n");
            for (LimitRange lr : limitRanges) {
                sb.append("  ").append(lr.getMetadata().getName()).append(":\n");
                List<LimitRangeItem> items = Optional.ofNullable(lr.getSpec())
                        .map(LimitRangeSpec::getLimits).orElse(Collections.emptyList());
                for (LimitRangeItem item : items) {
                    sb.append("    type=").append(item.getType()).append("\n");
                    appendQuantityMap("      default        ", item.getDefaultRequest(), sb);
                    appendQuantityMap("      defaultRequest ", item.getDefaultRequest(), sb);
                    appendQuantityMap("      min            ", item.getMin(), sb);
                    appendQuantityMap("      max            ", item.getMax(), sb);
                    appendQuantityMap("      maxLimitReqRatio", item.getMaxLimitRequestRatio(), sb);
                }
            }
        }

        sb.append("\n[SUSPICIONS]\n");
        if (quotas.isEmpty() && limitRanges.isEmpty()) {
            sb.append("  - No quotas or limit ranges — containers without explicit requests/limits\n");
            sb.append("    will be BestEffort and may OOM or consume all node resources.\n");
        }
        if (!limitRanges.isEmpty()) {
            sb.append("  - LimitRanges present: containers without explicit requests/limits\n");
            sb.append("    will receive injected defaults at admission time.\n");
        }
        quotas.forEach(q -> {
            Map<String, Quantity> hard = Optional.ofNullable(q.getStatus())
                    .map(ResourceQuotaStatus::getHard).orElse(Collections.emptyMap());
            Map<String, Quantity> used = Optional.ofNullable(q.getStatus())
                    .map(ResourceQuotaStatus::getUsed).orElse(Collections.emptyMap());
            hard.forEach((key, hardQ) -> {
                if (isNearLimit(used.get(key), hardQ)) {
                    sb.append("  - Quota '").append(key).append("' is near or at its hard limit — ")
                            .append("new pod admissions for this resource will be rejected.\n");
                }
            });
        });
        return sb.toString();
    }

    @LlmTool(
            name = "inspectNodeResourcePressure",
            description = """
                    Use this tool when nodes have MemoryPressure or DiskPressure conditions, when pods
                    are evicted, when scheduling fails with "insufficient memory/cpu", or when a node
                    is NotReady. Pass a specific node name or an empty string to inspect all nodes.
                    Returns allocatable/capacity, conditions, taints, and the number of pods scheduled.
                    """
    )
    public String inspectNodeResourcePressure(String nodeName) {
        List<Node> nodes;
        if (nodeName == null || nodeName.isBlank()) {
            nodes = kubernetesClient.nodes().list().getItems();
        } else {
            Node n = kubernetesClient.nodes().withName(nodeName).get();
            if (n == null) {
                return "ERROR: node " + nodeName + " not found.";
            }
            nodes = List.of(n);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== NODE RESOURCE PRESSURE ===\n\n");

        for (Node node : nodes) {
            String nName = node.getMetadata().getName();
            NodeStatus status = node.getStatus();

            sb.append("--- Node: ").append(nName).append(" ---\n");

            Map<String, Quantity> allocatable = Optional.ofNullable(status)
                    .map(NodeStatus::getAllocatable).orElse(Collections.emptyMap());
            Map<String, Quantity> capacity = Optional.ofNullable(status)
                    .map(NodeStatus::getCapacity).orElse(Collections.emptyMap());

            sb.append("  capacity:    cpu=").append(quantityStr(capacity.get("cpu")))
                    .append(" memory=").append(quantityStr(capacity.get("memory")))
                    .append(" ephemeral-storage=").append(quantityStr(capacity.get("ephemeral-storage")))
                    .append("\n");
            sb.append("  allocatable: cpu=").append(quantityStr(allocatable.get("cpu")))
                    .append(" memory=").append(quantityStr(allocatable.get("memory")))
                    .append(" ephemeral-storage=").append(quantityStr(allocatable.get("ephemeral-storage")))
                    .append("\n");

            List<NodeCondition> conditions = Optional.ofNullable(status)
                    .map(NodeStatus::getConditions).orElse(Collections.emptyList());
            sb.append("  conditions:\n");
            for (NodeCondition c : conditions) {
                boolean isTrue = "True".equals(c.getStatus());
                boolean isPressureOrNotReady = List.of("MemoryPressure", "DiskPressure", "PIDPressure").contains(c.getType())
                        ? isTrue
                        : "Ready".equals(c.getType()) && !isTrue;
                sb.append("    ").append(c.getType()).append("=").append(c.getStatus());
                if (isPressureOrNotReady) sb.append("  ⚠ ALERT");
                sb.append("\n");
            }

            List<Taint> taints = Optional.ofNullable(node.getSpec())
                    .map(NodeSpec::getTaints).orElse(Collections.emptyList());
            if (!taints.isEmpty()) {
                sb.append("  taints: ").append(
                        taints.stream().map(t -> t.getKey() + "=" + t.getValue() + ":" + t.getEffect())
                                .collect(Collectors.joining(", "))).append("\n");
            }

            long podCount = kubernetesClient.pods().inAnyNamespace()
                    .withField("spec.nodeName", nName).list().getItems().size();
            sb.append("  pods scheduled: ").append(podCount).append("\n\n");
        }

        sb.append("[SUSPICIONS]\n");
        for (Node node : nodes) {
            String nName = node.getMetadata().getName();
            List<NodeCondition> conditions = Optional.ofNullable(node.getStatus())
                    .map(NodeStatus::getConditions).orElse(Collections.emptyList());
            for (NodeCondition c : conditions) {
                if ("MemoryPressure".equals(c.getType()) && "True".equals(c.getStatus())) {
                    sb.append("  - ").append(nName).append(": MemoryPressure=True — kubelet may evict pods.")
                            .append(" Check node memory usage and eviction thresholds.\n");
                }
                if ("DiskPressure".equals(c.getType()) && "True".equals(c.getStatus())) {
                    sb.append("  - ").append(nName).append(": DiskPressure=True — check ephemeral storage,")
                            .append(" log volume, and image layer usage.\n");
                }
                if ("Ready".equals(c.getType()) && !"True".equals(c.getStatus())) {
                    sb.append("  - ").append(nName).append(": Not Ready — check kubelet logs and network.\n");
                }
            }
        }
        return sb.toString();
    }

    @LlmTool(
            name = "currentResourceUsage",
            description = """
                    Use this tool to get a live snapshot of CPU and memory consumption for pods and nodes
                    from the metrics-server (metrics.k8s.io). Useful for confirming OOM suspicions,
                    spotting hot containers, or comparing usage against requests/limits.
                    Pass a specific namespace or an empty string for all namespaces.
                    Returns metrics_unavailable with a diagnostic hint if metrics-server is absent.
                    """
    )
    public String currentResourceUsage(String namespaceOrEmpty) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== CURRENT RESOURCE USAGE ===\n\n");

        boolean hasNamespace = namespaceOrEmpty != null && !namespaceOrEmpty.isBlank();

        // Pod metrics
        try {
            List<PodMetrics> podMetricsList;
            if (hasNamespace) {
                podMetricsList = kubernetesClient.top().pods()
                        .metrics(namespaceOrEmpty).getItems();
            } else {
                podMetricsList = kubernetesClient.top().pods()
                        .metrics().getItems();
            }

            if (podMetricsList.isEmpty()) {
                sb.append("[Pod Metrics] no data returned — metrics-server may not have scraped yet\n");
            } else {
                sb.append("[Pod Metrics] namespace=").append(hasNamespace ? namespaceOrEmpty : "ALL").append("\n");
                for (PodMetrics pm : podMetricsList) {
                    String ns = pm.getMetadata().getNamespace();
                    String name = pm.getMetadata().getName();
                    sb.append("  ").append(ns).append("/").append(name).append(":\n");
                    for (ContainerMetrics cm : pm.getContainers()) {
                        String cpu = quantityStr(cm.getUsage().get("cpu"));
                        String mem = quantityStr(cm.getUsage().get("memory"));
                        sb.append("    ").append(cm.getName())
                                .append("  cpu=").append(cpu)
                                .append("  memory=").append(mem)
                                .append("\n");
                    }
                }
            }
        } catch (KubernetesClientException e) {
            sb.append("[Pod Metrics] metrics_unavailable — ")
                    .append(metricsUnavailableHint(e)).append("\n");
            log.debug("metrics.k8s.io pod metrics unavailable", e);
        }

        sb.append("\n");

        // Node metrics
        try {
            List<NodeMetrics> nodeMetricsList = kubernetesClient.top().nodes()
                    .metrics().getItems();

            if (nodeMetricsList.isEmpty()) {
                sb.append("[Node Metrics] no data returned\n");
            } else {
                sb.append("[Node Metrics]\n");
                for (NodeMetrics nm : nodeMetricsList) {
                    String cpu = quantityStr(nm.getUsage().get("cpu"));
                    String mem = quantityStr(nm.getUsage().get("memory"));
                    sb.append("  ").append(nm.getMetadata().getName())
                            .append("  cpu=").append(cpu)
                            .append("  memory=").append(mem)
                            .append("\n");
                }
            }
        } catch (KubernetesClientException e) {
            sb.append("[Node Metrics] metrics_unavailable — ")
                    .append(metricsUnavailableHint(e)).append("\n");
            log.debug("metrics.k8s.io node metrics unavailable", e);
        }

        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Pod evidence + suspicions
    // ──────────────────────────────────────────────────────────────────────────

    private void appendPodEvidence(StringBuilder sb, Pod pod) {
        PodStatus status = pod.getStatus();

        String phase = Optional.ofNullable(status).map(PodStatus::getPhase).orElse("Unknown");
        String qos = qosClass(pod);
        sb.append("  phase=").append(phase)
                .append("  qosClass=").append(qos).append("\n");

        List<Container> containers = Optional.ofNullable(pod.getSpec())
                .map(PodSpec::getContainers).orElse(Collections.emptyList());
        List<ContainerStatus> statuses = Optional.ofNullable(status)
                .map(PodStatus::getContainerStatuses).orElse(Collections.emptyList());

        Map<String, ContainerStatus> statusByName = statuses.stream()
                .collect(Collectors.toMap(ContainerStatus::getName, cs -> cs, (a, b) -> a));

        for (Container c : containers) {
            sb.append("  container: ").append(c.getName()).append("\n");

            ResourceRequirements res = c.getResources();
            if (res == null) {
                sb.append("    requests: <none>  limits: <none>  ⚠ NO RESOURCES SET\n");
            } else {
                Map<String, Quantity> req = Optional.ofNullable(res.getRequests()).orElse(Collections.emptyMap());
                Map<String, Quantity> lim = Optional.ofNullable(res.getLimits()).orElse(Collections.emptyMap());
                sb.append("    requests: cpu=").append(quantityStr(req.get("cpu")))
                        .append(" memory=").append(quantityStr(req.get("memory"))).append("\n");
                sb.append("    limits:   cpu=").append(quantityStr(lim.get("cpu")))
                        .append(" memory=").append(quantityStr(lim.get("memory"))).append("\n");
            }

            ContainerStatus cs = statusByName.get(c.getName());
            if (cs != null) {
                sb.append("    restarts: ").append(cs.getRestartCount()).append("\n");
                ContainerState state = cs.getState();
                if (state != null && state.getWaiting() != null) {
                    sb.append("    state: Waiting reason=").append(state.getWaiting().getReason()).append("\n");
                }
                if (state != null && state.getTerminated() != null) {
                    ContainerStateTerminated t = state.getTerminated();
                    sb.append("    state: Terminated reason=").append(t.getReason())
                            .append(" exitCode=").append(t.getExitCode()).append("\n");
                }
                ContainerState last = cs.getLastState();
                if (last != null && last.getTerminated() != null) {
                    ContainerStateTerminated lt = last.getTerminated();
                    sb.append("    lastTermination: reason=").append(lt.getReason())
                            .append(" exitCode=").append(lt.getExitCode())
                            .append(" finishedAt=").append(lt.getFinishedAt()).append("\n");
                    if ("OOMKilled".equals(lt.getReason()) || Integer.valueOf(137).equals(lt.getExitCode())) {
                        sb.append("    ⚠ OOMKilled detected in last termination\n");
                    }
                }
            }
        }

        List<Container> initContainers = Optional.ofNullable(pod.getSpec())
                .map(PodSpec::getInitContainers).orElse(Collections.emptyList());
        if (!initContainers.isEmpty()) {
            sb.append("  initContainers: ")
                    .append(initContainers.stream().map(Container::getName).collect(Collectors.joining(", ")))
                    .append("\n");
        }
    }

    private void appendPodSuspicions(StringBuilder sb, Pod pod) {
        sb.append("\n[SUSPICIONS]\n");
        List<Container> containers = Optional.ofNullable(pod.getSpec())
                .map(PodSpec::getContainers).orElse(Collections.emptyList());
        List<ContainerStatus> statuses = Optional.ofNullable(pod.getStatus())
                .map(PodStatus::getContainerStatuses).orElse(Collections.emptyList());
        Map<String, ContainerStatus> statusByName = statuses.stream()
                .collect(Collectors.toMap(ContainerStatus::getName, cs -> cs, (a, b) -> a));

        boolean anySuspicion = false;
        for (Container c : containers) {
            ContainerStatus cs = statusByName.get(c.getName());
            if (cs != null) {
                ContainerState last = cs.getLastState();
                if (last != null && last.getTerminated() != null) {
                    ContainerStateTerminated lt = last.getTerminated();
                    if ("OOMKilled".equals(lt.getReason()) || Integer.valueOf(137).equals(lt.getExitCode())) {
                        sb.append("  - Container '").append(c.getName())
                                .append("' was OOMKilled. Memory limit is too tight or the application has a memory leak.\n");
                        anySuspicion = true;
                    }
                }
                if (cs.getRestartCount() > 4) {
                    sb.append("  - Container '").append(c.getName())
                            .append("' has ").append(cs.getRestartCount())
                            .append(" restarts — likely CrashLoopBackOff. Investigate OOM or application crashes.\n");
                    anySuspicion = true;
                }
            }
            ResourceRequirements res = c.getResources();
            if (res == null || res.getLimits() == null || res.getLimits().isEmpty()) {
                sb.append("  - Container '").append(c.getName())
                        .append("' has no resource limits — it can consume all node memory and trigger OOM on the node.\n");
                anySuspicion = true;
            }
            if (res == null || res.getRequests() == null || res.getRequests().isEmpty()) {
                sb.append("  - Container '").append(c.getName())
                        .append("' has no resource requests — QoS is BestEffort, first to be evicted under pressure.\n");
                anySuspicion = true;
            }
        }

        String qos = qosClass(pod);
        if ("BestEffort".equals(qos)) {
            sb.append("  - Pod QoS is BestEffort (no requests or limits). Under node memory pressure,\n");
            sb.append("    this pod will be the first evicted by the kubelet.\n");
            anySuspicion = true;
        } else if ("Burstable".equals(qos)) {
            sb.append("  - Pod QoS is Burstable. Requests < limits. If usage exceeds limits, OOMKill occurs.\n");
            anySuspicion = true;
        }

        if (!anySuspicion) {
            sb.append("  - No obvious resource issues detected from static configuration.\n");
            sb.append("    Use currentResourceUsage to check live metrics.\n");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Workload selector resolution
    // ──────────────────────────────────────────────────────────────────────────

    private Map<String, String> resolveSelector(String namespace, String kind, String name) {
        return switch (kind.toLowerCase()) {
            case "deployment" -> {
                Deployment d = kubernetesClient.apps().deployments()
                        .inNamespace(namespace).withName(name).get();
                yield d == null ? null : Optional.ofNullable(d.getSpec())
                        .map(s -> s.getSelector())
                        .map(LabelSelector::getMatchLabels)
                        .orElse(null);
            }
            case "statefulset" -> {
                StatefulSet ss = kubernetesClient.apps().statefulSets()
                        .inNamespace(namespace).withName(name).get();
                yield ss == null ? null : Optional.ofNullable(ss.getSpec())
                        .map(s -> s.getSelector())
                        .map(LabelSelector::getMatchLabels)
                        .orElse(null);
            }
            case "daemonset" -> {
                DaemonSet ds = kubernetesClient.apps().daemonSets()
                        .inNamespace(namespace).withName(name).get();
                yield ds == null ? null : Optional.ofNullable(ds.getSpec())
                        .map(s -> s.getSelector())
                        .map(LabelSelector::getMatchLabels)
                        .orElse(null);
            }
            case "replicaset" -> {
                ReplicaSet rs = kubernetesClient.apps().replicaSets()
                        .inNamespace(namespace).withName(name).get();
                yield rs == null ? null : Optional.ofNullable(rs.getSpec())
                        .map(s -> s.getSelector())
                        .map(LabelSelector::getMatchLabels)
                        .orElse(null);
            }
            case "job" -> {
                Job j = kubernetesClient.batch().v1().jobs()
                        .inNamespace(namespace).withName(name).get();
                yield j == null ? null : Optional.ofNullable(j.getSpec())
                        .map(s -> s.getSelector())
                        .map(LabelSelector::getMatchLabels)
                        .orElse(null);
            }
            case "cronjob" -> {
                CronJob cj = kubernetesClient.batch().v1().cronjobs()
                        .inNamespace(namespace).withName(name).get();
                if (cj == null) yield null;
                yield Optional.ofNullable(cj.getSpec())
                        .map(s -> s.getJobTemplate())
                        .map(t -> t.getSpec())
                        .map(s -> s.getSelector())
                        .map(LabelSelector::getMatchLabels)
                        .orElse(null);
            }
            default -> null;
        };
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Events
    // ──────────────────────────────────────────────────────────────────────────

    private List<Event> fetchPodEvents(String namespace, String podName) {
        try {
            return kubernetesClient.v1().events()
                    .inNamespace(namespace)
                    .withField("involvedObject.name", podName)
                    .list()
                    .getItems()
                    .stream()
                    .sorted(Comparator.comparing(
                            e -> Optional.ofNullable(e.getLastTimestamp()).orElse(""),
                            Comparator.reverseOrder()))
                    .limit(MAX_EVENTS)
                    .toList();
        } catch (Exception e) {
            log.debug("Could not fetch events for pod {}/{}: {}", namespace, podName, e.getMessage());
            return Collections.emptyList();
        }
    }

    private void appendEvents(StringBuilder sb, List<Event> events) {
        if (events.isEmpty()) {
            sb.append("\n[Events] none found\n");
            return;
        }
        sb.append("\n[Events] (most recent first, max ").append(MAX_EVENTS).append(")\n");
        for (Event e : events) {
            sb.append("  [").append(e.getType()).append("] ")
                    .append(e.getReason()).append(": ")
                    .append(e.getMessage()).append("\n");
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private String qosClass(Pod pod) {
        return Optional.ofNullable(pod.getStatus())
                .map(PodStatus::getQosClass)
                .filter(s -> !s.isBlank())
                .orElseGet(() -> inferQosClass(pod));
    }

    private String inferQosClass(Pod pod) {
        List<Container> containers = Optional.ofNullable(pod.getSpec())
                .map(PodSpec::getContainers).orElse(Collections.emptyList());
        boolean hasAnyResources = containers.stream().anyMatch(c -> {
            ResourceRequirements r = c.getResources();
            return r != null && (notEmpty(r.getRequests()) || notEmpty(r.getLimits()));
        });
        if (!hasAnyResources) return "BestEffort";
        boolean allGuaranteed = containers.stream().allMatch(c -> {
            ResourceRequirements r = c.getResources();
            if (r == null) return false;
            Map<String, Quantity> req = r.getRequests();
            Map<String, Quantity> lim = r.getLimits();
            return notEmpty(req) && notEmpty(lim)
                    && Objects.equals(quantityStr(req.get("cpu")), quantityStr(lim.get("cpu")))
                    && Objects.equals(quantityStr(req.get("memory")), quantityStr(lim.get("memory")));
        });
        return allGuaranteed ? "Guaranteed" : "Burstable";
    }

    private boolean notEmpty(Map<String, Quantity> m) {
        return m != null && !m.isEmpty();
    }

    private boolean hasPodOOMKilled(Pod pod) {
        return Optional.ofNullable(pod.getStatus())
                .map(PodStatus::getContainerStatuses)
                .orElse(Collections.emptyList())
                .stream()
                .anyMatch(cs -> {
                    ContainerState last = cs.getLastState();
                    if (last == null || last.getTerminated() == null) return false;
                    ContainerStateTerminated t = last.getTerminated();
                    return "OOMKilled".equals(t.getReason()) || Integer.valueOf(137).equals(t.getExitCode());
                });
    }

    private int totalRestarts(Pod pod) {
        return Optional.ofNullable(pod.getStatus())
                .map(PodStatus::getContainerStatuses)
                .orElse(Collections.emptyList())
                .stream()
                .mapToInt(ContainerStatus::getRestartCount)
                .sum();
    }

    private boolean hasMissingLimits(Pod pod) {
        return Optional.ofNullable(pod.getSpec())
                .map(PodSpec::getContainers)
                .orElse(Collections.emptyList())
                .stream()
                .anyMatch(c -> c.getResources() == null || !notEmpty(c.getResources().getLimits()));
    }

    private boolean hasMissingRequests(Pod pod) {
        return Optional.ofNullable(pod.getSpec())
                .map(PodSpec::getContainers)
                .orElse(Collections.emptyList())
                .stream()
                .anyMatch(c -> c.getResources() == null || !notEmpty(c.getResources().getRequests()));
    }

    private String quantityStr(Quantity q) {
        if (q == null) return "<none>";
        String s = q.toString();
        return s == null || s.isBlank() ? "<none>" : s;
    }

    private void appendQuantityMap(String label, Map<String, Quantity> map, StringBuilder sb) {
        if (map == null || map.isEmpty()) return;
        sb.append(label).append(": ");
        sb.append(map.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + quantityStr(e.getValue()))
                .collect(Collectors.joining(" ")));
        sb.append("\n");
    }

    private boolean isNearLimit(Quantity used, Quantity hard) {
        if (used == null || hard == null) return false;
        try {
            double u = parseQuantityValue(used);
            double h = parseQuantityValue(hard);
            return h > 0 && u / h >= 0.85;
        } catch (Exception e) {
            return false;
        }
    }

    private double parseQuantityValue(Quantity q) {
        if (q == null) return 0;
        String s = q.toString();
        if (s == null || s.isBlank()) return 0;
        // Strip non-numeric suffix for simple comparison (m, Ki, Mi, Gi, etc.)
        // This is approximate; exact parsing would need a full quantity parser.
        s = s.replaceAll("[^0-9.]", "");
        return s.isBlank() ? 0 : Double.parseDouble(s);
    }

    private String metricsUnavailableHint(KubernetesClientException e) {
        int code = e.getCode();
        if (code == 404) {
            return "metrics.k8s.io API not found. Install metrics-server: "
                    + "kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml";
        }
        if (code == 403) {
            return "RBAC denied. Grant the service account 'get' on metrics.k8s.io/pods and metrics.k8s.io/nodes.";
        }
        return "HTTP " + code + ": " + e.getMessage();
    }
}

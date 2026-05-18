package dev.datrollout.argus.kubernetes.embabel;

import com.embabel.agent.api.annotation.LlmTool;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.api.model.apps.*;
import io.fabric8.kubernetes.api.model.batch.v1.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.PodResource;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LLM-callable tools for reading container logs.
 *
 * <p>Bounded output: every tool caps the response at {@link #MAX_BYTES_PER_CALL} bytes total
 * to keep the LLM token budget sane. When truncation happens, the response is annotated.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class LogsTool {

    private static final int DEFAULT_TAIL_LINES = 200;
    private static final int MAX_TAIL_LINES = 2000;
    private static final int MAX_BYTES_PER_CALL = 64 * 1024;
    private static final int MAX_GREP_MATCHES = 200;
    private static final int MAX_REPLICAS_TAILED = 10;

    private final KubernetesClient kubernetesClient;

    // ──────────────────────────────────────────────────────────────────────────
    // LLM tools
    // ──────────────────────────────────────────────────────────────────────────

    @LlmTool(name = "getPodLogs", description = """
                    Use this tool to read stdout/stderr logs from a single container in a pod.
                    Required for diagnosing WHY an application is misbehaving once you've already
                    identified WHAT is broken via inspectPodResourceHealth or inspectPodNetworking.
                    Pass the namespace, pod name, and container name. Use containerName="" to default
                    to the first container; if the pod has multiple containers, pass the exact name.
                    tailLines caps the trailing lines returned (default 200, max 2000).
                    sinceSeconds (>0) limits to logs newer than N seconds ago; pass 0 to ignore.
                    Output is hard-capped at 64 KiB and truncation is annotated. For previous
                    (crashed) container logs use getPreviousContainerLogs.
                    """)
    public String getPodLogs(String namespace, String podName, String containerName, int tailLines, int sinceSeconds) {
        Pod pod =
                kubernetesClient.pods().inNamespace(namespace).withName(podName).get();
        if (pod == null) {
            return "ERROR: pod " + namespace + "/" + podName + " not found.\n" + "Hint: call findPods(\""
                    + safeSearchTerm(podName) + "\") to locate the right pod.";
        }
        String resolvedContainer = resolveContainerName(pod, containerName);
        if (resolvedContainer == null) {
            return containerNotFoundMessage(pod, containerName);
        }

        int effectiveTail = clampTail(tailLines);
        StringBuilder sb = new StringBuilder();
        sb.append("=== POD LOGS: ")
                .append(namespace)
                .append("/")
                .append(podName)
                .append("  container=")
                .append(resolvedContainer)
                .append("  tailLines=")
                .append(effectiveTail);
        if (sinceSeconds > 0) sb.append("  sinceSeconds=").append(sinceSeconds);
        sb.append(" ===\n\n");

        String logs = fetchLog(namespace, podName, resolvedContainer, effectiveTail, sinceSeconds, false);
        if (logs == null) {
            sb.append("(no logs returned — container may not have started yet)\n");
        } else {
            appendBoundedLogs(sb, logs);
        }
        return sb.toString();
    }

    @LlmTool(name = "getPreviousContainerLogs", description = """
                    Use this tool ONLY for post-mortem of a CrashLoopBackOff or recently-restarted
                    container — it returns the stdout/stderr of the PREVIOUS instance (kubectl logs
                    --previous). The current instance's logs come from getPodLogs. Pass the
                    namespace, pod name, and container name (use "" for the first container). If
                    the container has never restarted, this returns a 'no previous instance' note.
                    Output is hard-capped at 64 KiB.
                    """)
    public String getPreviousContainerLogs(String namespace, String podName, String containerName) {
        Pod pod =
                kubernetesClient.pods().inNamespace(namespace).withName(podName).get();
        if (pod == null) {
            return "ERROR: pod " + namespace + "/" + podName + " not found.";
        }
        String resolvedContainer = resolveContainerName(pod, containerName);
        if (resolvedContainer == null) {
            return containerNotFoundMessage(pod, containerName);
        }

        int restarts = restartCount(pod, resolvedContainer);
        StringBuilder sb = new StringBuilder();
        sb.append("=== PREVIOUS CONTAINER LOGS: ")
                .append(namespace)
                .append("/")
                .append(podName)
                .append("  container=")
                .append(resolvedContainer)
                .append("  restarts=")
                .append(restarts)
                .append(" ===\n\n");

        if (restarts <= 0) {
            sb.append("(container has not restarted — there is no previous instance to read)\n");
            sb.append("Use getPodLogs for the current instance's logs.\n");
            return sb.toString();
        }

        String logs = fetchLog(namespace, podName, resolvedContainer, clampTail(DEFAULT_TAIL_LINES), 0, true);
        if (logs == null || logs.isEmpty()) {
            sb.append("(kubelet returned no previous-log content — log may have been rotated\n");
            sb.append(" or the container terminated before writing anything to stdout/stderr)\n");
            return sb.toString();
        }
        appendBoundedLogs(sb, logs);
        return sb.toString();
    }

    @LlmTool(name = "getWorkloadLogs", description = """
                    Use this tool to tail logs across ALL pods of a workload (Deployment,
                    StatefulSet, DaemonSet, ReplicaSet, Job, CronJob). Useful when one replica
                    succeeds and another fails, or when you don't yet know which replica is sick.
                    Returns the last 'tailLines' lines from each replica (default 200, max 2000),
                    capped to 10 replicas to keep output manageable. Each block is prefixed with
                    its pod name. Total output is hard-capped at 64 KiB.
                    """)
    public String getWorkloadLogs(String namespace, String kind, String name, int tailLines) {
        Map<String, String> selector = resolveSelector(namespace, kind, name);
        if (selector == null) {
            return "ERROR: could not find " + kind + " " + namespace + "/" + name
                    + " or selector resolution is unsupported for kind " + kind + ".\n"
                    + "Supported kinds: Deployment, StatefulSet, DaemonSet, ReplicaSet, Job, CronJob.";
        }
        if (selector.isEmpty()) {
            return "ERROR: " + kind + " " + namespace + "/" + name + " has no matchLabels selector.";
        }

        List<Pod> pods = kubernetesClient
                .pods()
                .inNamespace(namespace)
                .withLabels(selector)
                .list()
                .getItems();
        if (pods.isEmpty()) {
            return "No pods found for " + kind + " " + namespace + "/" + name + " with selector " + selector + ".";
        }

        int effectiveTail = clampTail(tailLines);
        StringBuilder sb = new StringBuilder();
        sb.append("=== WORKLOAD LOGS: ")
                .append(kind)
                .append(" ")
                .append(namespace)
                .append("/")
                .append(name)
                .append("  pods=")
                .append(pods.size())
                .append("  tailLines=")
                .append(effectiveTail)
                .append(" ===\n");
        if (pods.size() > MAX_REPLICAS_TAILED) {
            sb.append("(showing first ")
                    .append(MAX_REPLICAS_TAILED)
                    .append(" replicas of ")
                    .append(pods.size())
                    .append(")\n");
        }
        sb.append("\n");

        int budget = MAX_BYTES_PER_CALL;
        int tailed = 0;
        for (Pod pod : pods) {
            if (tailed >= MAX_REPLICAS_TAILED) break;
            tailed++;
            String podName = pod.getMetadata().getName();
            String container = primaryContainerName(pod);
            sb.append("--- pod: ").append(podName);
            if (container != null) sb.append("  container: ").append(container);
            sb.append(" ---\n");
            if (container == null) {
                sb.append("(pod has no containers)\n\n");
                continue;
            }
            if (budget <= 0) {
                sb.append("(skipped — global 64 KiB output budget reached)\n\n");
                continue;
            }
            String logs = fetchLog(namespace, podName, container, effectiveTail, 0, false);
            if (logs == null || logs.isEmpty()) {
                sb.append("(no logs)\n\n");
                continue;
            }
            int spend = Math.clamp(budget / Math.max(1, MAX_REPLICAS_TAILED - tailed + 1), 1024, logs.length());
            String slice = logs.length() > spend
                    ? "...[truncated " + (logs.length() - spend) + " bytes]...\n"
                            + logs.substring(logs.length() - spend)
                    : logs;
            sb.append(slice);
            if (!slice.endsWith("\n")) sb.append("\n");
            sb.append("\n");
            budget -= slice.length();
        }
        return sb.toString();
    }

    @LlmTool(name = "grepPodLogs", description = """
                    Use this tool to search a single container's logs for a regex pattern WITHOUT
                    pulling the entire log stream into the LLM. Server-side fetch, client-side
                    regex match — only matching lines are returned. Ideal for hunting stack traces,
                    error codes, or specific request IDs in noisy logs. Pattern is a Java regex
                    (case-insensitive). tailLines bounds how many recent lines are scanned
                    (default 200, max 2000). Returns up to 200 matched lines.
                    """)
    public String grepPodLogs(String namespace, String podName, String containerName, String pattern, int tailLines) {
        if (pattern == null || pattern.isBlank()) {
            return "ERROR: pattern must not be empty. Pass a Java regex like 'ERROR|Exception|timeout'.";
        }
        Pod pod =
                kubernetesClient.pods().inNamespace(namespace).withName(podName).get();
        if (pod == null) {
            return "ERROR: pod " + namespace + "/" + podName + " not found.";
        }
        String resolvedContainer = resolveContainerName(pod, containerName);
        if (resolvedContainer == null) {
            return containerNotFoundMessage(pod, containerName);
        }
        Pattern compiled;
        try {
            compiled = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return "ERROR: invalid regex '" + pattern + "': " + e.getMessage();
        }

        int effectiveTail = clampTail(tailLines);
        String logs = fetchLog(namespace, podName, resolvedContainer, effectiveTail, 0, false);
        StringBuilder sb = new StringBuilder();
        sb.append("=== GREP POD LOGS: ")
                .append(namespace)
                .append("/")
                .append(podName)
                .append("  container=")
                .append(resolvedContainer)
                .append("  pattern=/")
                .append(pattern)
                .append("/i")
                .append("  scanned=")
                .append(effectiveTail)
                .append(" lines ===\n\n");

        if (logs == null || logs.isEmpty()) {
            sb.append("(no logs available to grep)\n");
            return sb.toString();
        }

        int matched = 0;
        int truncatedExtra = 0;
        for (String line : logs.split("\n", -1)) {
            Matcher m = compiled.matcher(line);
            if (!m.find()) continue;
            if (matched >= MAX_GREP_MATCHES) {
                truncatedExtra++;
                continue;
            }
            sb.append(line).append('\n');
            matched++;
            if (sb.length() > MAX_BYTES_PER_CALL) {
                sb.setLength(MAX_BYTES_PER_CALL);
                sb.append("\n... (output truncated at 64 KiB)\n");
                return sb.toString();
            }
        }

        sb.append("\n[matches=").append(matched);
        if (truncatedExtra > 0) sb.append(" (").append(truncatedExtra).append(" additional matches not shown)");
        sb.append("]\n");
        if (matched == 0) {
            sb.append("Hint: pattern did not match. Try widening it (case is already insensitive)\n");
            sb.append("      or increase tailLines (max 2000).\n");
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internals
    // ──────────────────────────────────────────────────────────────────────────

    private String fetchLog(
            String namespace, String podName, String container, int tailLines, int sinceSeconds, boolean previous) {
        try {
            PodResource res = kubernetesClient.pods().inNamespace(namespace).withName(podName);
            var stage = res.inContainer(container);
            var afterPrev = previous ? stage.terminated() : stage;
            var afterSince = sinceSeconds > 0 ? afterPrev.sinceSeconds(sinceSeconds) : afterPrev;
            return afterSince.tailingLines(tailLines).getLog();
        } catch (KubernetesClientException e) {
            log.debug("Could not fetch logs for {}/{} container {}: {}", namespace, podName, container, e.getMessage());
            return "ERROR: " + describeLogError(e);
        }
    }

    private String describeLogError(KubernetesClientException e) {
        int code = e.getCode();
        if (code == 400) {
            return "kubelet rejected the log request (400). The container may not have started, "
                    + "or there is no previous instance to read.";
        }
        if (code == 403) return "RBAC denied. Grant 'get' on pods/log.";
        if (code == 404) return "pod or container not found at the kubelet.";
        return "HTTP " + code + ": " + e.getMessage();
    }

    private void appendBoundedLogs(StringBuilder sb, String logs) {
        if (logs.length() <= MAX_BYTES_PER_CALL) {
            sb.append(logs);
            if (!logs.endsWith("\n")) sb.append('\n');
            return;
        }
        int kept = MAX_BYTES_PER_CALL;
        int dropped = logs.length() - kept;
        sb.append("...[truncated ").append(dropped).append(" bytes from the head; showing tail]...\n");
        sb.append(logs, logs.length() - kept, logs.length());
        if (!logs.endsWith("\n")) sb.append('\n');
    }

    private int clampTail(int tailLines) {
        if (tailLines <= 0) return DEFAULT_TAIL_LINES;
        return Math.min(tailLines, MAX_TAIL_LINES);
    }

    private String resolveContainerName(Pod pod, String containerName) {
        List<Container> containers =
                Optional.ofNullable(pod.getSpec()).map(PodSpec::getContainers).orElse(Collections.emptyList());
        if (containers.isEmpty()) return null;
        if (containerName == null || containerName.isBlank()) {
            return containers.getFirst().getName();
        }
        return containers.stream()
                .map(Container::getName)
                .filter(n -> n.equals(containerName))
                .findFirst()
                .orElse(null);
    }

    private String primaryContainerName(Pod pod) {
        return Optional.ofNullable(pod.getSpec()).map(PodSpec::getContainers).orElse(Collections.emptyList()).stream()
                .findFirst()
                .map(Container::getName)
                .orElse(null);
    }

    private String containerNotFoundMessage(Pod pod, String requested) {
        List<String> names =
                Optional.ofNullable(pod.getSpec()).map(PodSpec::getContainers).orElse(Collections.emptyList()).stream()
                        .map(Container::getName)
                        .sorted(Comparator.naturalOrder())
                        .toList();
        return "ERROR: container '" + (requested == null ? "" : requested) + "' not found in pod "
                + pod.getMetadata().getNamespace() + "/" + pod.getMetadata().getName()
                + ". Available containers: " + (names.isEmpty() ? "<none>" : names);
    }

    private int restartCount(Pod pod, String container) {
        return Optional.ofNullable(pod.getStatus())
                .map(PodStatus::getContainerStatuses)
                .orElse(Collections.emptyList())
                .stream()
                .filter(cs -> container.equals(cs.getName()))
                .mapToInt(ContainerStatus::getRestartCount)
                .findFirst()
                .orElse(0);
    }

    private String safeSearchTerm(String s) {
        return s == null ? "" : s.replaceAll("[^a-zA-Z0-9-]", "");
    }

    private Map<String, String> resolveSelector(String namespace, String kind, String name) {
        return switch (kind == null ? "" : kind.toLowerCase()) {
            case "deployment" -> {
                Deployment d = kubernetesClient
                        .apps()
                        .deployments()
                        .inNamespace(namespace)
                        .withName(name)
                        .get();
                yield d == null
                        ? null
                        : Optional.ofNullable(d.getSpec())
                                .map(DeploymentSpec::getSelector)
                                .map(LabelSelector::getMatchLabels)
                                .orElse(Collections.emptyMap());
            }
            case "statefulset" -> {
                StatefulSet ss = kubernetesClient
                        .apps()
                        .statefulSets()
                        .inNamespace(namespace)
                        .withName(name)
                        .get();
                yield ss == null
                        ? null
                        : Optional.ofNullable(ss.getSpec())
                                .map(StatefulSetSpec::getSelector)
                                .map(LabelSelector::getMatchLabels)
                                .orElse(Collections.emptyMap());
            }
            case "daemonset" -> {
                DaemonSet ds = kubernetesClient
                        .apps()
                        .daemonSets()
                        .inNamespace(namespace)
                        .withName(name)
                        .get();
                yield ds == null
                        ? null
                        : Optional.ofNullable(ds.getSpec())
                                .map(DaemonSetSpec::getSelector)
                                .map(LabelSelector::getMatchLabels)
                                .orElse(Collections.emptyMap());
            }
            case "replicaset" -> {
                ReplicaSet rs = kubernetesClient
                        .apps()
                        .replicaSets()
                        .inNamespace(namespace)
                        .withName(name)
                        .get();
                yield rs == null
                        ? null
                        : Optional.ofNullable(rs.getSpec())
                                .map(ReplicaSetSpec::getSelector)
                                .map(LabelSelector::getMatchLabels)
                                .orElse(Collections.emptyMap());
            }
            case "job" -> {
                Job j = kubernetesClient
                        .batch()
                        .v1()
                        .jobs()
                        .inNamespace(namespace)
                        .withName(name)
                        .get();
                yield j == null
                        ? null
                        : Optional.ofNullable(j.getSpec())
                                .map(JobSpec::getSelector)
                                .map(LabelSelector::getMatchLabels)
                                .orElse(Collections.emptyMap());
            }
            case "cronjob" -> {
                CronJob cj = kubernetesClient
                        .batch()
                        .v1()
                        .cronjobs()
                        .inNamespace(namespace)
                        .withName(name)
                        .get();
                if (cj == null) yield null;
                yield Optional.ofNullable(cj.getSpec())
                        .map(CronJobSpec::getJobTemplate)
                        .map(JobTemplateSpec::getSpec)
                        .map(JobSpec::getSelector)
                        .map(LabelSelector::getMatchLabels)
                        .orElse(Collections.emptyMap());
            }
            default -> null;
        };
    }
}

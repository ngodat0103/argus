package dev.datrollout.argus.kubernetes.persistence;

import com.embabel.agent.api.annotation.LlmTool;
import dev.datrollout.argus.kubernetes.phase.runtime.ContainerMemoryKillEventWrapper;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;

/**
 * OOMKilled incident at the pod/container level.
 * Covers: container OOM, init-container OOM, sidecar OOM.
 */
@Entity
@Table(name = "incident_container_oom")
@Getter
@Setter
@NoArgsConstructor
public class ContainerMemoryKubernetesIncident extends KubernetesIncidentReport {

    // ─── Container Identity ──────────────────────────────────────────────────
    private String podName;
    private String podUid;
    private String containerName;
    private boolean initContainer;

    // ─── OOM Signal ──────────────────────────────────────────────────────────
    private int restartCount;
    private int restartCountAtDetection; // snapshot to distinguish new vs old crash
    private String exitCode; // "137" (OOM), "1", etc.
    private String terminationReason; // "OOMKilled" verbatim from ContainerStatus
    private String terminationMessage; // From containerState.terminated.message

    // ─── Memory Configuration ────────────────────────────────────────────────
    private long requestMemoryBytes;
    private long limitMemoryBytes;
    private long observedRssBytes; // From cAdvisor / metrics API if available
    private double limitUtilizationPct; // observedRss / limit * 100

    // ─── Diagnosis ───────────────────────────────────────────────────────────
    private String imageRef; // Full image SHA for reproducibility
    private String lastLogTailBeforeCrash; // Last N lines before OOM, LLM input
    private boolean memoryLeakSuspected; // LLM triage output
    private boolean burstWorkloadSuspected;

    // ─── Suggested Remediation ───────────────────────────────────────────────
    private long suggestedLimitBytes;
    private String limitIncreasePct; // e.g. "50%" — for the approval gate message

    public static ContainerMemoryKubernetesIncident fromContainerMemoryEventWrapper(
            ContainerMemoryKillEventWrapper containerMemoryKillEventWrapper) {
        ContainerMemoryKubernetesIncident incident = new ContainerMemoryKubernetesIncident();

        String containerName = containerMemoryKillEventWrapper.getFailedContainerName();
        Pod failedPod = containerMemoryKillEventWrapper.getFailedPod();

        // ─── Container Identity ──────────────────────────────────────────────
        incident.setPodName(containerMemoryKillEventWrapper.getPodName());
        if (failedPod != null && failedPod.getMetadata() != null) {
            incident.setPodUid(failedPod.getMetadata().getUid());
        }
        incident.setContainerName(containerName);
        incident.setInitContainer(containerMemoryKillEventWrapper.isInitContainerFailure());

        // ─── OOM Signal ──────────────────────────────────────────────────────
        Integer restartCount = containerMemoryKillEventWrapper.getContainerRestartCount();
        int restarts = restartCount != null ? restartCount : 0;
        incident.setRestartCount(restarts);
        incident.setRestartCountAtDetection(restarts);

        ContainerStateTerminated terminated = terminatedState(failedPod, containerName);
        if (terminated != null) {
            incident.setExitCode(terminated.getExitCode() != null ? String.valueOf(terminated.getExitCode()) : "137");
            incident.setTerminationReason(terminated.getReason() != null ? terminated.getReason() : "OOMKilled");
            incident.setTerminationMessage(
                    terminated.getMessage() != null
                            ? terminated.getMessage()
                            : containerMemoryKillEventWrapper.getMessage());
        } else {
            incident.setExitCode("137");
            incident.setTerminationReason("OOMKilled");
            incident.setTerminationMessage(containerMemoryKillEventWrapper.getMessage());
        }

        // ─── Memory Configuration ────────────────────────────────────────────
        ContainerMemoryKillEventWrapper.MemoryConfig memoryConfig = containerMemoryKillEventWrapper.getMemoryConfig();
        if (memoryConfig != null) {
            if (memoryConfig.getMemoryRequestBytes() != null) {
                incident.setRequestMemoryBytes(memoryConfig.getMemoryRequestBytes());
            }
            if (memoryConfig.getMemoryLimitBytes() != null) {
                incident.setLimitMemoryBytes(memoryConfig.getMemoryLimitBytes());
            }
        }

        // ─── Diagnosis ───────────────────────────────────────────────────────
        incident.setImageRef(resolveImageRef(failedPod, containerName));
        List<String> lineLogs = containerMemoryKillEventWrapper.getLineLogs();
        if (lineLogs != null && !lineLogs.isEmpty()) {
            incident.setLastLogTailBeforeCrash(String.join("\n", lineLogs));
        }

        // ─── Base incident fields ────────────────────────────────────────────
        incident.setNamespace(containerMemoryKillEventWrapper.getNamespace());
        incident.setNodeName(containerMemoryKillEventWrapper.getNodeName());
        incident.setDetectedAt(Instant.now());
        incident.setDetectionSource(DetectionSource.WATCHER);
        incident.setStatus(IncidentStatus.DETECTED);
        return incident;
    }

    /** Prefer the current terminated state, falling back to the last terminated state. */
    private static ContainerStateTerminated terminatedState(Pod pod, String containerName) {
        ContainerStatus status = containerStatus(pod, containerName);
        if (status == null) {
            return null;
        }
        return Optional.ofNullable(status.getState())
                .map(ContainerState::getTerminated)
                .or(() -> Optional.ofNullable(status.getLastState()).map(ContainerState::getTerminated))
                .orElse(null);
    }

    private static ContainerStatus containerStatus(Pod pod, String containerName) {
        if (pod == null || pod.getStatus() == null || containerName == null) {
            return null;
        }
        return java.util.stream.Stream.of(
                        pod.getStatus().getContainerStatuses(),
                        pod.getStatus().getInitContainerStatuses(),
                        pod.getStatus().getEphemeralContainerStatuses())
                .filter(java.util.Objects::nonNull)
                .flatMap(List::stream)
                .filter(cs -> containerName.equals(cs.getName()))
                .findFirst()
                .orElse(null);
    }

    private static String resolveImageRef(Pod pod, String containerName) {
        ContainerStatus status = containerStatus(pod, containerName);
        if (status != null
                && status.getImageID() != null
                && !status.getImageID().isEmpty()) {
            return status.getImageID();
        }
        if (pod != null && pod.getSpec() != null && containerName != null) {
            return pod.getSpec().getContainers().stream()
                    .filter(c -> containerName.equals(c.getName()))
                    .findFirst()
                    .map(Container::getImage)
                    .orElse(null);
        }
        return null;
    }

    @LlmTool(description = "Returns the memory request configured for this container.")
    public String getContainerMemoryRequest() {
        if (this.requestMemoryBytes == 0) {
            return "No memory request set";
        }
        return this.requestMemoryBytes + " bytes (" + (this.requestMemoryBytes / (1024 * 1024)) + " MiB)";
    }

    @LlmTool(description = "Returns the memory limit configured for this container.")
    public String getContainerMemoryLimit() {
        if (this.limitMemoryBytes == 0) {
            return "No memory limit set (container can use unlimited node memory)";
        }
        return this.limitMemoryBytes + " bytes (" + (this.limitMemoryBytes / (1024 * 1024)) + " MiB)";
    }

    @LlmTool(description = "Returns the termination signal details: exit code, reason, and any termination message.")
    public String getTerminationDetails() {
        StringBuilder sb = new StringBuilder();
        sb.append("Exit code: ").append(exitCode != null ? exitCode : "unknown");
        sb.append(", Reason: ").append(terminationReason != null ? terminationReason : "unknown");
        if (terminationMessage != null && !terminationMessage.isBlank()) {
            sb.append(", Message: ").append(terminationMessage);
        }
        return sb.toString();
    }

    @LlmTool(
            description = "Returns restart count history for this container to help determine if the OOM is recurring.")
    public String getRestartHistory() {
        int newCrashes = Math.max(0, restartCount - restartCountAtDetection);
        return "Current restart count: " + restartCount
                + ". Restarts since detection: " + newCrashes
                + (restartCount > 5 ? " — high restart count, likely recurring." : ".");
    }

    @LlmTool(description = "Returns the container log lines captured immediately before the OOMKill crash.")
    public String getLastContainerLogs() {
        if (lastLogTailBeforeCrash == null || lastLogTailBeforeCrash.isBlank()) {
            return "No logs captured before the crash.";
        }
        return lastLogTailBeforeCrash;
    }

    @Override
    protected String incidentTypeTag() {
        return "container-memory-killed";
    }

    @Override
    public @NotNull String contribution() {
        StringBuilder sb = new StringBuilder();
        sb.append("## OOMKilled Incident Context\n\n");

        sb.append("- **Namespace:** ")
                .append(getNamespace() != null ? getNamespace() : "unknown")
                .append("\n");
        sb.append("- **Pod:** ").append(podName != null ? podName : "unknown").append("\n");
        sb.append("- **Container:** ").append(containerName != null ? containerName : "unknown");
        sb.append(initContainer ? " (init container)" : " (regular container)").append("\n");
        sb.append("- **Termination:** ").append(terminationReason != null ? terminationReason : "OOMKilled");
        sb.append(" (exit code ").append(exitCode != null ? exitCode : "137").append(")");
        sb.append(", restart count: ").append(restartCount).append("\n");

        if (limitMemoryBytes > 0) {
            sb.append("- **Memory Limit:** ")
                    .append(limitMemoryBytes)
                    .append(" bytes (")
                    .append(limitMemoryBytes / (1024 * 1024))
                    .append(" MiB)\n");
        } else {
            sb.append("- **Memory Limit:** No limit set\n");
        }
        if (requestMemoryBytes > 0) {
            sb.append("- **Memory Request:** ")
                    .append(requestMemoryBytes)
                    .append(" bytes (")
                    .append(requestMemoryBytes / (1024 * 1024))
                    .append(" MiB)\n");
        } else {
            sb.append("- **Memory Request:** No request set\n");
        }

        sb.append("- **Image:** ")
                .append(imageRef != null ? imageRef : "unknown")
                .append("\n");

        if (memoryLeakSuspected || burstWorkloadSuspected) {
            sb.append("- **Pre-classified:** ");
            if (memoryLeakSuspected) sb.append("memory leak suspected");
            if (memoryLeakSuspected && burstWorkloadSuspected) sb.append(", ");
            if (burstWorkloadSuspected) sb.append("burst workload suspected");
            sb.append("\n");
        }

        sb.append("\nUse the following tools to gather evidence before drawing conclusions:\n");
        sb.append("- `getContainerMemoryRequest()` / `getContainerMemoryLimit()` — confirm resource configuration\n");
        sb.append("- `getTerminationDetails()` — full termination signal\n");
        sb.append("- `getRestartHistory()` — assess recurrence pattern\n");
        sb.append("- `getLastContainerLogs()` — application output immediately before the crash\n");

        return sb.toString();
    }
}

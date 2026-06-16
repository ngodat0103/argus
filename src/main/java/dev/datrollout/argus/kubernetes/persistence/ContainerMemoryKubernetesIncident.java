package dev.datrollout.argus.kubernetes.persistence;

import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.common.ai.prompt.PromptContribution;
import com.embabel.common.ai.prompt.PromptContributionLocation;
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
import org.jetbrains.annotations.Nullable;

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

    // Todo it should support methods that LLm can use, tailor with @LLmTool like below

    @LlmTool
    public String getContainerMemoryRequest(){
        return this.requestMemoryBytes + " bytes";
    }
    @Override
    protected String incidentTypeTag() {
        return "container-memory-killed";
    }

    @Override
    public @NotNull String contribution() {
        return ""; // Todo context tailor for LLM friendly, it should have instruct to tell LLM to use tools for details
    }

}

package dev.datrollout.argus.incidentManipulation.event;

import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.api.tool.Tool;
import io.fabric8.kubernetes.api.model.*;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

public class ContainerMemoryKillKubernetesEvent extends KubernetesEvent {

    private final String failedContainerName;

    public ContainerMemoryKillKubernetesEvent(Pod associatedPod, String failedContainerName) {
        super(associatedPod);
        this.failedContainerName = failedContainerName;
    }

    // ===== LlmReference metadata =====

    @Override
    public @NotNull String getName() {
        // Must be unique within a prompt — work key + container disambiguates.
        return "oomkill:" + getWorkKey() + "/" + failedContainerName;
    }

    @Override
    public @NotNull String getDescription() {
        return "An OOMKilled (exit 137) container '%s'. Exposes tools to inspect its memory "
                        .formatted(failedContainerName)
                + "limits/requests, restart history, QoS class and sibling containers, "
                + "so you can diagnose why it ran out of memory.";
    }

    @Override
    public @NotNull String notes() {
        return """
               Use these tools to diagnose the OOMKill before proposing a fix.
               Typical flow: check the configured memory limit, then restart count and
               QoS class, then whether sibling containers compete for the same node budget.
               A missing limit or a limit far below the working set is the most common cause.
               """;
    }

    @Override
    public @NotNull String toolPrefix() {
        // Override the default so tools stay unique when two containers in the same
        // pod are OOMKilled and both references land in one prompt.
        return "oomkill_" + failedContainerName.replaceAll("[^a-zA-Z0-9]", "_");
    }

    // ===== @Tool methods: LLM-facing diagnostics backed by the encapsulated Pod =====

    @LlmTool(description = "Configured memory limit of the OOMKilled container (e.g. '512Mi'), or 'none' if unset.")
    public String memoryLimit() {
        return resourceQuantity(ResourceRequirements::getLimits);
    }

    @LlmTool(description = "Configured memory request of the OOMKilled container, or 'none' if unset.")
    public String memoryRequest() {
        return resourceQuantity(ResourceRequirements::getRequests);
    }

    @LlmTool(
            description =
                    "Number of times the container has restarted. A high count points to a crash loop, not a one-off spike.")
    public int restartCount() {
        return failedContainerStatus().map(ContainerStatus::getRestartCount).orElse(0);
    }

    @LlmTool(
            description = "Reason and exit code from the last termination. OOMKill is reason=OOMKilled, exit code 137.")
    public String lastTermination() {
        return failedContainerStatus()
                .map(ContainerStatus::getLastState)
                .map(ContainerState::getTerminated)
                .map(t -> "reason=%s exitCode=%d startedAt=%s finishedAt=%s"
                        .formatted(t.getReason(), t.getExitCode(), t.getStartedAt(), t.getFinishedAt()))
                .orElse("no termination record");
    }

    @LlmTool(
            description =
                    "Pod QoS class. BestEffort/Burstable pods are killed first under node memory pressure; Guaranteed are most protected.")
    public String qosClass() {
        return Optional.ofNullable(associatedPod)
                .map(Pod::getStatus)
                .map(PodStatus::getQosClass)
                .orElse("unknown");
    }

    @LlmTool(
            description =
                    "Other containers in the same pod with their memory limits, to check whether they compete for the node's memory.")
    public String siblingContainers() {
        if (associatedPod == null || associatedPod.getSpec() == null) return "none";
        String siblings = associatedPod.getSpec().getContainers().stream()
                .filter(c -> !failedContainerName.equals(c.getName()))
                .map(c -> "%s (memory limit: %s)".formatted(c.getName(), limitOf(c)))
                .collect(Collectors.joining(", "));
        return siblings.isBlank() ? "none" : siblings;
    }

    @LlmTool(description = "Node the pod is scheduled on, for correlating with node-level memory pressure.")
    public String nodeName() {
        return Optional.ofNullable(associatedPod)
                .map(Pod::getSpec)
                .map(s -> s.getNodeName())
                .orElse("unscheduled");
    }

    @LlmTool(
            description =
                    "The workload (Deployment/StatefulSet/etc.) that owns this pod — the thing you'd actually patch to raise the limit.")
    public String ownerWorkload() {
        if (associatedPod == null || associatedPod.getMetadata() == null) return "unknown";
        var owners = associatedPod.getMetadata().getOwnerReferences();
        if (owners == null || owners.isEmpty()) return "none (bare pod)";
        return owners.stream().map(o -> o.getKind() + "/" + o.getName()).collect(Collectors.joining(", "));
    }

    // ===== internal helpers (never exposed to the LLM) =====

    private Optional<Container> failedContainerSpec() {
        return Optional.ofNullable(associatedPod).map(Pod::getSpec).flatMap(spec -> spec.getContainers().stream()
                .filter(c -> failedContainerName.equals(c.getName()))
                .findFirst());
    }

    private Optional<ContainerStatus> failedContainerStatus() {
        return Optional.ofNullable(associatedPod).map(Pod::getStatus).flatMap(st -> st.getContainerStatuses().stream()
                .filter(s -> failedContainerName.equals(s.getName()))
                .findFirst());
    }

    private String resourceQuantity(
            java.util.function.Function<ResourceRequirements, java.util.Map<String, Quantity>> selector) {
        return failedContainerSpec()
                .map(Container::getResources)
                .map(selector)
                .map(m -> m.get("memory"))
                .map(Quantity::toString)
                .orElse("none");
    }

    private String limitOf(Container c) {
        return Optional.ofNullable(c.getResources())
                .map(ResourceRequirements::getLimits)
                .map(m -> m.get("memory"))
                .map(Quantity::toString)
                .orElse("none");
    }

    @Override
    public @NotNull List<com.embabel.agent.api.tool.Tool> tools() {
        return Tool.fromInstance(this);
    }
}

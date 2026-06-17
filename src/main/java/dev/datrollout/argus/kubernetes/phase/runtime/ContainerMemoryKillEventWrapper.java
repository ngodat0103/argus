package dev.datrollout.argus.kubernetes.phase.runtime;

import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.api.tool.Tool;
import com.embabel.common.util.StringTransformer;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

@Slf4j
public class ContainerMemoryKillEventWrapper extends LoggablePodEventWrapper {

    private final String containerName;

    public ContainerMemoryKillEventWrapper(String containerName, Pod associatedPod) {
        super();
        this.setAssociatedPod(associatedPod);
        this.containerName = containerName;
    }

    @LlmTool
    public String getOOMExplanation() {
        String containerName = getFailedContainerName();
        if (containerName == null || associatedPod == null) {
            return "Cannot determine OOM details";
        }

        MemoryConfig config = this.getMemoryConfig();
        if (config == null) {
            return "Cannot determine memory configuration";
        }

        StringBuilder explanation = new StringBuilder();
        explanation.append("Container '").append(containerName).append("' was OOM killed.\n\n");

        if (!config.hasMemoryLimit) {
            explanation.append("NO MEMORY LIMIT SET - Container can use unlimited memory until node runs" + " out.\n");
            explanation.append("Recommendation: Set memory limits to prevent node exhaustion.\n");
        } else {
            explanation.append("Memory Limit: ").append(config.memoryLimit).append("\n"); // Use original string
            explanation.append("The container exceeded this limit and was killed by Kubernetes.\n");
        }

        if (config.hasMemoryRequest) {
            explanation
                    .append("\nMemory Request: ")
                    .append(config.memoryRequest)
                    .append("\n"); // Use original string
            explanation.append("This is the guaranteed memory allocated to the container.\n");

            if (config.hasMemoryLimit && config.memoryLimitBytes != null && config.memoryRequestBytes != null) {
                double ratio = (double) config.memoryLimitBytes / config.memoryRequestBytes;
                explanation.append(String.format("Limit/Request Ratio: %.2fx\n", ratio));

                if (ratio > 2.0) {
                    explanation.append("Large ratio - container can burst significantly above request.\n");
                }
            }
        } else {
            explanation.append("\nNO MEMORY REQUEST SET - No guaranteed memory allocation.\n");
        }

        Integer restartCount = getContainerRestartCount();
        if (restartCount != null && restartCount > 0) {
            explanation.append("\nRestart Count: ").append(restartCount).append("\n");
            if (restartCount > 5) {
                explanation.append("High restart count - recurring OOM issue.\n");
            }
        }
        return explanation.toString();
    }

    public MemoryConfig getMemoryConfig() {
        String containerName = getFailedContainerName();
        if (associatedPod == null || containerName == null) {
            return null;
        }

        Container container = associatedPod.getSpec().getContainers().stream()
                .filter(c -> containerName.equals(c.getName()))
                .findFirst()
                .orElse(null);

        if (container == null || container.getResources() == null) {
            return MemoryConfig.builder()
                    .containerName(containerName)
                    .hasMemoryLimit(false)
                    .hasMemoryRequest(false)
                    .build();
        }

        Map<String, Quantity> requests = container.getResources().getRequests();
        Map<String, Quantity> limits = container.getResources().getLimits();

        Quantity memoryRequest = requests != null ? requests.get("memory") : null;
        Quantity memoryLimit = limits != null ? limits.get("memory") : null;

        return MemoryConfig.builder()
                .containerName(containerName)
                .memoryRequest(memoryRequest != null ? memoryRequest.toString() : null)
                .memoryLimit(memoryLimit != null ? memoryLimit.toString() : null)
                .memoryRequestBytes(parseMemoryToBytes(memoryRequest))
                .memoryLimitBytes(parseMemoryToBytes(memoryLimit))
                .hasMemoryLimit(memoryLimit != null)
                .hasMemoryRequest(memoryRequest != null)
                .build();
    }

    /** Get restart count for the failed container */
    public Integer getContainerRestartCount() {
        String containerName = getFailedContainerName();
        if (associatedPod == null
                || containerName == null
                || associatedPod.getStatus() == null
                || associatedPod.getStatus().getContainerStatuses() == null) {
            return null;
        }

        return associatedPod.getStatus().getContainerStatuses().stream()
                .filter(cs -> containerName.equals(cs.getName()))
                .findFirst()
                .map(ContainerStatus::getRestartCount)
                .orElse(null);
    }
    /** Convert Kubernetes memory quantity to bytes */
    private Long parseMemoryToBytes(Quantity quantity) {
        if (quantity == null || quantity.getAmount() == null) {
            return null;
        }

        String amount = quantity.getAmount();
        try {
            // Handle different units: Ki, Mi, Gi, K, M, G
            if (amount.endsWith("Ki")) {
                return Long.parseLong(amount.replace("Ki", "")) * 1024L;
            } else if (amount.endsWith("Mi")) {
                return Long.parseLong(amount.replace("Mi", "")) * 1024L * 1024L;
            } else if (amount.endsWith("Gi")) {
                return Long.parseLong(amount.replace("Gi", "")) * 1024L * 1024L * 1024L;
            } else if (amount.endsWith("K")) {
                return Long.parseLong(amount.replace("K", "")) * 1000L;
            } else if (amount.endsWith("M")) {
                return Long.parseLong(amount.replace("M", "")) * 1000L * 1000L;
            } else if (amount.endsWith("G")) {
                return Long.parseLong(amount.replace("G", "")) * 1000L * 1000L * 1000L;
            } else {
                // Plain bytes
                return Long.parseLong(amount);
            }
        } catch (Exception e) {
            log.warn("Failed to parse memory quantity: {}", amount, e);
            return null;
        }
    }

    @Override
    public String getFailedContainerName() {
        return this.containerName;
    }

    @Override
    public @NotNull String notes() {
        return "";
    }

    @Override
    public @NotNull String getDescription() {
        return "";
    }

    @Override
    public @NotNull String getName() {
        return "";
    }

    @Override
    public @NotNull List<Tool> tools() {
        return super.tools();
    }

    @Override
    public @NotNull String contribution() {
        return super.contribution();
    }

    @Override
    public @NotNull String toolPrefix() {
        return super.toolPrefix();
    }

    @Override
    public @NotNull StringTransformer getNamingStrategy() {
        return super.getNamingStrategy();
    }


    /** Memory configuration data class */
    @Data
    @Builder
    public static class MemoryConfig {
        private String containerName;
        private String memoryRequest;
        private String memoryLimit;
        private Long memoryRequestBytes;
        private Long memoryLimitBytes;
        private boolean hasMemoryLimit;
        private boolean hasMemoryRequest;
    }

    @Getter
    public enum MemoryIssue {
        NO_LIMIT("No memory limit set"),
        NO_REQUEST("No memory request set"),
        LIMIT_EXCEEDED("Container exceeded memory limit"),
        UNKNOWN("Cannot determine issue");
        private final String description;

        MemoryIssue(String description) {
            this.description = description;
        }
    }
}

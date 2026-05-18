package dev.datrollout.argus.kubernetes.embabel;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.progressive.UnfoldingTool;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KubernetesResourceUnfoldingTool implements UnfoldingTool {
    private final MetricsResourceTool metricsResourceTool;

    @Override
    public @NotNull List<Tool> getInnerTools() {
        return Tool.fromInstance(metricsResourceTool);
    }

    @Override
    public @NotNull Result call(@NotNull String input) {
        return Result.text("CPU and memory diagnostic tools are now available. "
                + "Use them to investigate OOMKilled pods, CrashLoopBackOff, CPU/memory requests and limits, "
                + "node memory/disk pressure, namespace CPU/memory quota exhaustion, and live consumption metrics.");
    }

    @Override
    public @NotNull Definition getDefinition() {
        String name = "kubernetes-cpu-memory-diagnostics";
        String description = """
                Entry point for CPU and memory troubleshooting tools on Kubernetes. \
                Invoke this when the operator reports OOMKilled pods, CrashLoopBackOff, \
                high restart counts, pods stuck in Pending due to insufficient CPU or memory, \
                node MemoryPressure or DiskPressure, namespace CPU/memory quota admission failures, \
                or when you need a live CPU/memory consumption snapshot from the metrics-server. \
                Exposes: inspectPodResourceHealth, inspectWorkloadResourceHealth, \
                inspectNamespaceResourceConstraints, inspectNodeResourcePressure, \
                currentResourceUsage.\
                """;
        return Definition.create(name, description, InputSchema.empty());
    }
}

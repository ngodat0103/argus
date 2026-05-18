package dev.datrollout.argus.kubernetes.embabel;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.progressive.UnfoldingTool;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SchedulingDiagnosticsUnfoldingTool implements UnfoldingTool {
    private final SchedulingDiagnosticsTool schedulingDiagnosticsTool;

    @Override
    public @NotNull List<Tool> getInnerTools() {
        return Tool.fromInstance(schedulingDiagnosticsTool);
    }

    @Override
    public @NotNull Result call(@NotNull String input) {
        return Result.text("Scheduling diagnostic tools are now available.\n"
                + "Use explainPodScheduling to walk a Pending pod against every node and find which "
                + "constraint (nodeSelector, nodeAffinity, taints, cordon, NotReady) is rejecting it. "
                + "Use listPodDisruptionBudgets to find PDBs that block drains, HPA scale-down, or kubelet "
                + "evictions. These are scheduler-view tools — for resource fit (CPU/memory) on the chosen "
                + "node use inspectNodeResourcePressure or estimatePodFitCount.");
    }

    @Override
    public @NotNull Definition getDefinition() {
        String name = "kubernetes-scheduling-diagnostics";
        String description = """
                Entry point for scheduling and disruption-budget diagnostics. Invoke this when the \
                operator reports a pod stuck in Pending despite cluster having capacity, a node \
                drain that hangs, an HPA that can't scale down, or admission events like \
                FailedScheduling, NoNodesAvailable, or PDB violations. \
                Exposes: explainPodScheduling, listPodDisruptionBudgets.\
                """;
        return Definition.create(name, description, InputSchema.empty());
    }
}

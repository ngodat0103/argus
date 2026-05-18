package dev.datrollout.argus.kubernetes.embabel;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.progressive.UnfoldingTool;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WorkloadStateUnfoldingTool implements UnfoldingTool {
    private final WorkloadStateTool workloadStateTool;

    @Override
    public @NotNull List<Tool> getInnerTools() {
        return Tool.fromInstance(workloadStateTool);
    }

    @Override
    public @NotNull Result call(@NotNull String input) {
        return Result.text("Workload rollout / state tools are now available.\n"
                + "Use inspectDeploymentRollout for stuck or paused Deployments, getRolloutHistory to see "
                + "the ReplicaSet revision trail (and how to rollback), inspectStatefulSetState for ordinal-by-"
                + "ordinal rollout progress and partition canaries, and inspectDaemonSetState for missing or "
                + "misscheduled per-node pods. These complement (don't replace) inspectPodResourceHealth — "
                + "rollout state is about whether the controller is making progress, pod health is about "
                + "whether the pods themselves are happy.");
    }

    @Override
    public @NotNull Definition getDefinition() {
        String name = "kubernetes-workload-rollout";
        String description = """
                Entry point for diagnosing workload rollout progress and revision history. Invoke \
                this when the operator asks "did my deployment go live", "is the rollout stuck", \
                "show me the rollout history", "why is StatefulSet ordinal 2 not updating", or \
                "the DaemonSet is missing on some nodes". Returns desired/observed/updated/ready \
                replica state, conditions (Progressing, Available), strategy parameters \
                (RollingUpdate vs Recreate, partition, maxSurge, maxUnavailable), ReplicaSet \
                revisions with change-cause, and per-ordinal / per-node breakdowns. \
                Exposes: inspectDeploymentRollout, getRolloutHistory, inspectStatefulSetState, \
                inspectDaemonSetState.\
                """;
        return Definition.create(name, description, InputSchema.empty());
    }
}

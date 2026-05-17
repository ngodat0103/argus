package dev.datrollout.argus.kubernetes.embabel;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.progressive.UnfoldingTool;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class EventsUnfoldingTool implements UnfoldingTool {
    private final EventsTool eventsTool;

    @Override
    public @NotNull List<Tool> getInnerTools() {
        return Tool.fromInstance(eventsTool);
    }

    @Override
    public @NotNull Result call(@NotNull String input) {
        return Result.text(
                "Cluster Event tools are now available. Always start an incident investigation here — " +
                "Events surface admission rejections, scheduler decisions, image pull failures, probe " +
                "failures, OOM kills, and controller errors before anything else.\n" +
                "Use listRecentEvents for a namespace-scoped Warning feed, listClusterEvents for a " +
                "cluster-wide Warning feed (default Warning-only to keep the signal-to-noise ratio sane), " +
                "and findEventsForObject when you already know exactly which object is failing."
        );
    }

    @Override
    public @NotNull Definition getDefinition() {
        String name = "kubernetes-cluster-events";
        String description = """
                Entry point for inspecting Kubernetes Events as a first-class incident signal. \
                Invoke this when the operator says any variant of "what just broke", "show me \
                warnings", "the cluster is sad", or when triaging a specific object that is not \
                behaving (Pending pod, stuck PVC, failed Job). Events tell you WHY the controller \
                made the decision it did. \
                Exposes: listRecentEvents, listClusterEvents, findEventsForObject.\
                """;
        return Definition.create(name, description, InputSchema.empty());
    }
}

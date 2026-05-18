package dev.datrollout.argus.kubernetes.embabel;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.progressive.UnfoldingTool;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LogsUnfoldingTool implements UnfoldingTool {
    private final LogsTool logsTool;

    @Override
    public @NotNull List<Tool> getInnerTools() {
        return Tool.fromInstance(logsTool);
    }

    @Override
    public @NotNull Result call(@NotNull String input) {
        return Result.text("Container log tools are now available. Output is hard-capped at 64 KiB per call "
                + "and tail length is capped at 2000 lines so a single call cannot blow the LLM budget.\n"
                + "Use getPodLogs for the current instance, getPreviousContainerLogs for crash post-mortems, "
                + "getWorkloadLogs to fan out across replicas of a Deployment/StatefulSet/DaemonSet/Job/CronJob, "
                + "and grepPodLogs to filter server-fetched logs by regex before they reach the LLM.");
    }

    @Override
    public @NotNull Definition getDefinition() {
        String name = "kubernetes-container-logs";
        String description = """
                Entry point for reading container stdout/stderr in Kubernetes. Invoke this whenever \
                you need to know WHAT an application is actually saying — error messages, stack \
                traces, request IDs, panic output, application-level diagnostics. Required for \
                root-causing CrashLoopBackOff (use getPreviousContainerLogs), application-level \
                500s (grepPodLogs for ERROR/Exception), and slow startup (tail current logs). \
                Exposes: getPodLogs, getPreviousContainerLogs, getWorkloadLogs, grepPodLogs.\
                """;
        return Definition.create(name, description, InputSchema.empty());
    }
}

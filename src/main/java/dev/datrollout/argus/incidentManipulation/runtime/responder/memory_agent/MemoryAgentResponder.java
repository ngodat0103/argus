package dev.datrollout.argus.incidentManipulation.runtime.responder.memory_agent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.core.ActionRetryPolicy;
import dev.datrollout.argus.embabel.ChatAction;
import dev.datrollout.argus.incidentManipulation.event.ContainerMemoryKillKubernetesEvent;
import dev.datrollout.argus.incidentManipulation.runtime.persistence.ContainerMemoryKubernetesIncident;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;

@Agent(
        description = "A Agent to investigate and fix memory issues",
        planner = PlannerType.GOAP,
        actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE)
@RequiredArgsConstructor
public class MemoryAgentResponder {
    private final KubernetesClient kubernetesClient;
    private static final String INVESTIGATION_PROMPT = """
        A Memory incident have been detected, using the Tools to investigate. After that generate Execution summary For Senior DevOps Engineer to review
        """;

    @Action
    @AchievesGoal(description = "A report about memory issues")
    public ContainerMemoryKubernetesIncident investigateMemoryIssues(
            OperationContext operationContext, ContainerMemoryKillKubernetesEvent containerMemoryKillKubernetesEvent) {
        String executionSummary = operationContext
                .ai()
                .withLlmByRole("reasoning")
                .withPromptContributor(ChatAction.coStar)
                .withReference(containerMemoryKillKubernetesEvent)
                .generateText(INVESTIGATION_PROMPT);
        ContainerMemoryKubernetesIncident containerMemoryKubernetesIncident = new ContainerMemoryKubernetesIncident();
        containerMemoryKubernetesIncident.setExecutionSummary(executionSummary);
        return containerMemoryKubernetesIncident;
    }
}

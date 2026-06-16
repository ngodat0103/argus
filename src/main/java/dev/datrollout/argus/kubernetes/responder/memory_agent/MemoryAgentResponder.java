package dev.datrollout.argus.kubernetes.responder.memory_agent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.core.ActionRetryPolicy;
import com.embabel.common.ai.model.LlmOptions;
import dev.datrollout.argus.kubernetes.persistence.ContainerMemoryKubernetesIncident;
import dev.datrollout.argus.kubernetes.phase.runtime.ContainerMemoryKillEventWrapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.prompt.PromptTemplate;

import java.util.Map;

@Agent(
        description = "A Agent to investigate and fix memory issues",
        planner = PlannerType.GOAP,
        actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE)
@RequiredArgsConstructor
public class MemoryAgentResponder {
    private final KubernetesClient kubernetesClient;
    private final PromptTemplate PROMPT_TEMPLATE = new PromptTemplate("""
            place_holder here
            including basic information about the incident like namespace,PodName
            {namespace}
            {podName}
            """);

    @Action
    @AchievesGoal(description = "A report about memory issues")
    public ContainerMemoryKubernetesIncident investigateMemoryIssues(
            OperationContext operationContext, ContainerMemoryKillEventWrapper containerMemoryKillEventWrapper) {
        ContainerMemoryKubernetesIncident containerMemoryKubernetesIncident =
                ContainerMemoryKubernetesIncident.fromContainerMemoryEventWrapper(containerMemoryKillEventWrapper);
        Map<String,Object> parameters = Map.of("namespace", containerMemoryKubernetesIncident.getNamespace(), "podName", containerMemoryKubernetesIncident.getPodName());
        String renderedPrompt = PROMPT_TEMPLATE.render(parameters);
        String executionSummary = operationContext
                .ai()
                .withDefaultLlm()
                .withPromptContributor(containerMemoryKubernetesIncident)
                .withToolObject(containerMemoryKubernetesIncident)
                .generateText(renderedPrompt);
        return containerMemoryKubernetesIncident;
    }
}

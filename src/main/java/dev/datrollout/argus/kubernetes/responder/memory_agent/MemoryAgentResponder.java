package dev.datrollout.argus.kubernetes.responder.memory_agent;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.core.ActionRetryPolicy;
import dev.datrollout.argus.kubernetes.persistence.ContainerMemoryKubernetesIncident;
import dev.datrollout.argus.kubernetes.phase.runtime.ContainerMemoryKillEventWrapper;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.prompt.PromptTemplate;

@Agent(
        description = "A Agent to investigate and fix memory issues",
        planner = PlannerType.GOAP,
        actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE)
@RequiredArgsConstructor
public class MemoryAgentResponder {
    private final KubernetesClient kubernetesClient;
    private final PromptTemplate PROMPT_TEMPLATE = new PromptTemplate("""
            You are an autonomous SRE agent responding to a Kubernetes OOMKilled incident.

            ## Incident
            - Namespace: {namespace}
            - Pod: {podName}

            ## Investigation Steps
            Use the tools available to you to diagnose the incident:
            1. Call getContainerMemoryRequest() and getContainerMemoryLimit() to review the resource configuration.
            2. Call getTerminationDetails() to confirm the OOMKill signal and exit code.
            3. Call getRestartHistory() to assess whether this is a recurring issue.
            4. Call getLastContainerLogs() to inspect the application behavior immediately before the crash.

            ## Root-Cause Classification
            Determine which category applies:
            - Memory leak: gradual RSS growth, high restart count, no clear burst trigger
            - Burst workload: sudden spike, low restart count, typically load-correlated
            - Misconfigured limit: limit is too low relative to normal steady-state usage

            ## Required Output
            Produce a concise incident summary containing:
            - Root cause hypothesis and confidence (0.0-1.0)
            - Whether immediate remediation is required
            - Suggested memory limit in bytes and the percentage increase over current limit
            - Whether the change requires human approval before being applied
            """);

    @Action
    @AchievesGoal(description = "A report about memory issues")
    public ContainerMemoryKubernetesIncident investigateMemoryIssues(
            OperationContext operationContext, ContainerMemoryKillEventWrapper containerMemoryKillEventWrapper) {
        ContainerMemoryKubernetesIncident containerMemoryKubernetesIncident =
                ContainerMemoryKubernetesIncident.fromContainerMemoryEventWrapper(containerMemoryKillEventWrapper);
        Map<String, Object> parameters = Map.of(
                "namespace",
                containerMemoryKubernetesIncident.getNamespace(),
                "podName",
                containerMemoryKubernetesIncident.getPodName());
        String renderedPrompt = PROMPT_TEMPLATE.render(parameters);
        String executionSummary = operationContext
                .ai()
                .withDefaultLlm()
                .withPromptContributor(containerMemoryKubernetesIncident)
                .withToolObject(containerMemoryKubernetesIncident)
                .generateText(renderedPrompt);
        containerMemoryKubernetesIncident.setExecutionSummary(executionSummary);
        return containerMemoryKubernetesIncident;
    }
}

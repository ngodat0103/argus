package dev.datrollout.argus.incidentManipulation.runtime.responder.memory_agent;

import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.common.OperationContext;
import dev.datrollout.argus.embabel.Orchestrator;
import dev.datrollout.argus.incidentManipulation.KubernetesEventResponder;
import dev.datrollout.argus.incidentManipulation.event.ContainerMemoryKillKubernetesEvent;
import dev.datrollout.argus.incidentManipulation.event.KubernetesEvent;
import dev.datrollout.argus.incidentManipulation.runtime.persistence.ContainerMemoryKubernetesIncident;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ContainerMemoryKillEventResponder extends KubernetesEventResponder {
    private static final String INVESTIGATION_PROMPT = """
        A Memory incident have been detected, using the Tools to investigate. After that generate Execution summary For Senior DevOps Engineer to review
        """;

    public ContainerMemoryKubernetesIncident investigateMemoryIssues(
            OperationContext operationContext, ContainerMemoryKillKubernetesEvent containerMemoryKillKubernetesEvent) {
        String executionSummary = operationContext
                .ai()
                .withLlmByRole("reasoning")
                .withPromptContributor(Orchestrator.coStar)
                .withReference(containerMemoryKillKubernetesEvent)
                .generateText(INVESTIGATION_PROMPT);
        ContainerMemoryKubernetesIncident containerMemoryKubernetesIncident = new ContainerMemoryKubernetesIncident();
        containerMemoryKubernetesIncident.setExecutionSummary(executionSummary);
        return containerMemoryKubernetesIncident;
    }

    @Override
    protected boolean isSupport(KubernetesEvent event) {
        return false;
    }

    @Override
    protected void doInternal(
            KubernetesEvent kubernetesEvent, OperationContext operationContext, ActionContext actionContext) {

    }
}

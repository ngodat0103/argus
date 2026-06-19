package dev.datrollout.argus.incidentManipulation.runtime.responder.memory_agent;

import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.chat.AssistantMessage;
import dev.datrollout.argus.embabel.PromptRegistry;
import dev.datrollout.argus.incidentManipulation.KubernetesEventResponder;
import dev.datrollout.argus.incidentManipulation.event.ContainerMemoryKillKubernetesEvent;
import dev.datrollout.argus.incidentManipulation.event.KubernetesEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ContainerMemoryKillEventResponder extends KubernetesEventResponder {
    private static final String INVESTIGATION_PROMPT = """
        A Memory incident have been detected, using the Tools to investigate. After that generate Execution summary For Senior DevOps Engineer to review
        """;

    @Override
    protected boolean isSupport(KubernetesEvent kubernetesEvent) {
        return kubernetesEvent instanceof ContainerMemoryKillKubernetesEvent;
    }

    @Override
    protected AssistantMessage generateAssistantMessageInternal(
            KubernetesEvent kubernetesEvent, OperationContext operationContext, ActionContext actionContext) {
        String executionSummary = operationContext
                .ai()
                .withLlmByRole("reasoning")
                .withPromptContributor(PromptRegistry.coStar)
                .withReference(kubernetesEvent)
                .generateText(INVESTIGATION_PROMPT);
        AssistantMessage assistantMessage = new AssistantMessage(executionSummary, "Argus");
        return assistantMessage;
    }
}

package dev.datrollout.argus.incidentManipulation;

import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.chat.AssistantMessage;
import dev.datrollout.argus.incidentManipulation.event.KubernetesEvent;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class KubernetesEventResponder {
    private KubernetesEventResponder nextResponder = null;

    public void setNext(KubernetesEventResponder nextResponder) {
        if (this.nextResponder != null) {
            throw new IllegalStateException("KubernetesEventResponder already set nextResponder");
        }
        this.nextResponder = nextResponder;
    }

    protected abstract boolean isSupport(KubernetesEvent kubernetesEvent);

    protected abstract AssistantMessage generateAssistantMessageInternal(
            KubernetesEvent kubernetesEvent, OperationContext operationContext, ActionContext actionContext);

    public void respond(
            KubernetesEvent kubernetesEvent, OperationContext operationContext, ActionContext actionContext) {
        if (isSupport(kubernetesEvent)) {
            log.info(
                    "Handling kubernetesEvent [{}] with responder [{}]",
                    kubernetesEvent.getWorkKey(),
                    getClass().getSimpleName());
            AssistantMessage assistantMessage =
                    this.generateAssistantMessageInternal(kubernetesEvent, operationContext, actionContext);
            actionContext.sendAndSave(assistantMessage);
        } else if (nextResponder != null) {
            log.debug(
                    "Responder [{}] does not support kubernetesEvent [{}], delegating to [{}]",
                    getClass().getSimpleName(),
                    kubernetesEvent.getWorkKey(),
                    nextResponder.getClass().getSimpleName());
            nextResponder.respond(kubernetesEvent, operationContext, actionContext);
        } else {
            log.warn(
                    "No responder found for kubernetesEvent [{}] — chain exhausted at [{}]",
                    kubernetesEvent.getWorkKey(),
                    getClass().getSimpleName());
        }
    }
}

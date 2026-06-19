package dev.datrollout.argus.incidentManipulation;

import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.common.OperationContext;
import dev.datrollout.argus.incidentManipulation.event.KubernetesEvent;

public abstract class KubernetesEventResponder {
    private KubernetesEventResponder nextResponder;

    public KubernetesEventResponder setNext(KubernetesEventResponder nextResponder) {
        this.nextResponder = nextResponder;
        return this;
    }

    protected abstract boolean isSupport(KubernetesEvent event);

    protected abstract void doInternal(
            KubernetesEvent kubernetesEvent, OperationContext operationContext, ActionContext actionContext);

    public void respond(KubernetesEvent event, OperationContext operationContext, ActionContext actionContext) {
        if (isSupport(event)) {
            this.doInternal(event, operationContext, actionContext);
        } else if (nextResponder != null) {
            nextResponder.respond(event, operationContext, actionContext);
        }
    }
}

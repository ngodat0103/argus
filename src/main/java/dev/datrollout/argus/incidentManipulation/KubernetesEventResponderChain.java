package dev.datrollout.argus.incidentManipulation;

import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.common.OperationContext;
import dev.datrollout.argus.incidentManipulation.event.KubernetesEvent;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class KubernetesEventResponderChain {
    private final KubernetesEventResponder firstResponder;

    public KubernetesEventResponderChain(List<KubernetesEventResponder> responders) {
        for (int i = 0; i < responders.size() - 1; i++) {
            KubernetesEventResponder responder = responders.get(i);
            responder.setNext(responders.get(i + 1));
        }
        this.firstResponder = responders.getFirst();
    }

    public void delegate(
            KubernetesEvent kubernetesEvent, OperationContext operationContext, ActionContext actionContext) {
        this.firstResponder.respond(kubernetesEvent, operationContext, actionContext);
    }
}

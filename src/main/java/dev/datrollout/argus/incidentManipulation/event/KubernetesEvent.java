package dev.datrollout.argus.incidentManipulation.event;

import com.embabel.agent.api.reference.LlmReference;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public abstract class KubernetesEvent extends ApplicationEvent implements LlmReference {
    protected Pod associatedPod;
    protected Event associatedEvent;

    public KubernetesEvent(Pod associatedPod) {
        super(associatedPod);
        this.associatedPod = associatedPod;
    }

    public KubernetesEvent(Event associatedEvent) {
        super(associatedEvent);
        this.associatedEvent = associatedEvent;
    }

    public final String getWorkKey() {
        if (associatedPod != null) {
            return "pod/%s/%s"
                    .formatted(
                            associatedPod.getMetadata().getNamespace(),
                            associatedPod.getMetadata().getName());
        }
        if (associatedEvent != null) {
            return "event/%s/%s"
                    .formatted(
                            associatedEvent.getMetadata().getNamespace(),
                            associatedEvent.getMetadata().getName());
        }
        throw new IllegalStateException("AbstractKubernetesEvent has no associated resource");
    }
}

package dev.datrollout.argus.observedetection.client.event;

import dev.datrollout.argus.observedetection.model.DetectionResult;
import org.springframework.context.ApplicationEvent;

public class CapabilityDetectedEvent extends ApplicationEvent {

    private final DetectionResult result;

    public CapabilityDetectedEvent(Object source, DetectionResult result) {
        super(source);
        this.result = result;
    }

    public DetectionResult getResult() {
        return result;
    }
}

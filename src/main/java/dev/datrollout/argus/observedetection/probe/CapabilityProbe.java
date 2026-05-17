package dev.datrollout.argus.observedetection.probe;

import dev.datrollout.argus.observedetection.model.DetectionResult;
import dev.datrollout.argus.observedetection.model.ProbeTarget;

public interface CapabilityProbe {

    String name();

    boolean supports(ProbeTarget target);

    DetectionResult probe(ProbeTarget target);
}

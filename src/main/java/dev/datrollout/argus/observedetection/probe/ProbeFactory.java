package dev.datrollout.argus.observedetection.probe;

import dev.datrollout.argus.observedetection.model.ProbeTarget;

import java.util.List;

public interface ProbeFactory {

    List<CapabilityProbe> probesFor(ProbeTarget target);
}

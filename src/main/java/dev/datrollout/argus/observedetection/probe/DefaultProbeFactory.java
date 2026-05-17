package dev.datrollout.argus.observedetection.probe;

import dev.datrollout.argus.observedetection.model.ProbeTarget;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DefaultProbeFactory implements ProbeFactory {

    private final List<CapabilityProbe> allProbes;

    public DefaultProbeFactory(List<CapabilityProbe> allProbes) {
        this.allProbes = List.copyOf(allProbes);
    }

    @Override
    public List<CapabilityProbe> probesFor(ProbeTarget target) {
        return allProbes.stream()
                .filter(probe -> probe.supports(target))
                .toList();
    }
}

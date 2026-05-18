package dev.datrollout.argus.observedetection.registry;

import dev.datrollout.argus.observedetection.model.Capability;
import dev.datrollout.argus.observedetection.model.DetectionResult;
import java.net.URI;
import java.util.List;
import java.util.Optional;

public interface CapabilityRegistry {

    void register(DetectionResult result);

    List<DetectionResult> findByCapability(Capability capability);

    Optional<DetectionResult> findByEndpoint(URI endpoint);

    List<DetectionResult> getAll();
}

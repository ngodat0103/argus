package dev.datrollout.argus.observedetection.client.factory;

import dev.datrollout.argus.observedetection.client.ObservabilityClient;
import dev.datrollout.argus.observedetection.model.DetectionResult;

public interface ClientFactory {

    boolean supports(DetectionResult result);

    ObservabilityClient build(DetectionResult result);
}

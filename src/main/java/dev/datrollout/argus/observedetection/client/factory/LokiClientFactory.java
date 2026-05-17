package dev.datrollout.argus.observedetection.client.factory;

import dev.datrollout.argus.observedetection.client.ObservabilityClient;
import dev.datrollout.argus.observedetection.client.impl.RestClientLokiClient;
import dev.datrollout.argus.observedetection.model.Capability;
import dev.datrollout.argus.observedetection.model.DetectionResult;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class LokiClientFactory implements ClientFactory {

    private final RestClient restClient;

    public LokiClientFactory(RestClient discoveryRestClient) {
        this.restClient = discoveryRestClient;
    }

    @Override
    public boolean supports(DetectionResult result) {
        return result.hasCapability(Capability.LOGQL_QUERY)
                || result.hasCapability(Capability.LOG_STREAMING);
    }

    @Override
    public ObservabilityClient build(DetectionResult result) {
        return new RestClientLokiClient(result.getEndpoint(), restClient);
    }
}

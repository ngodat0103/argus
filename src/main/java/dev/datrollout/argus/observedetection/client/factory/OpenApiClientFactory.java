package dev.datrollout.argus.observedetection.client.factory;

import dev.datrollout.argus.observedetection.client.ObservabilityClient;
import dev.datrollout.argus.observedetection.client.impl.RestClientOpenApiClient;
import dev.datrollout.argus.observedetection.model.Capability;
import dev.datrollout.argus.observedetection.model.DetectionResult;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OpenApiClientFactory implements ClientFactory {

    private final RestClient restClient;

    public OpenApiClientFactory(RestClient discoveryRestClient) {
        this.restClient = discoveryRestClient;
    }

    @Override
    public boolean supports(DetectionResult result) {
        return result.hasCapability(Capability.OPENAPI_DISCOVERY);
    }

    @Override
    public ObservabilityClient build(DetectionResult result) {
        return new RestClientOpenApiClient(result.getEndpoint(), restClient);
    }
}

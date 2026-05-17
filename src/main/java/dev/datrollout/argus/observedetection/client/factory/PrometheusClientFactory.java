package dev.datrollout.argus.observedetection.client.factory;

import dev.datrollout.argus.observedetection.client.ObservabilityClient;
import dev.datrollout.argus.observedetection.client.impl.RestClientPrometheusClient;
import dev.datrollout.argus.observedetection.model.Capability;
import dev.datrollout.argus.observedetection.model.DetectionResult;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PrometheusClientFactory implements ClientFactory {

    private final RestClient restClient;

    public PrometheusClientFactory(RestClient discoveryRestClient) {
        this.restClient = discoveryRestClient;
    }

    @Override
    public boolean supports(DetectionResult result) {
        return result.hasCapability(Capability.PROMQL_QUERY)
                || result.hasCapability(Capability.RANGE_QUERY);
    }

    @Override
    public ObservabilityClient build(DetectionResult result) {
        return new RestClientPrometheusClient(result.getEndpoint(), restClient);
    }
}

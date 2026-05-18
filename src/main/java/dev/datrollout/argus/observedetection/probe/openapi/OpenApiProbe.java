package dev.datrollout.argus.observedetection.probe.openapi;

import dev.datrollout.argus.observedetection.config.DiscoveryProperties;
import dev.datrollout.argus.observedetection.model.Capability;
import dev.datrollout.argus.observedetection.model.DetectionResult;
import dev.datrollout.argus.observedetection.model.ProbeTarget;
import dev.datrollout.argus.observedetection.probe.CapabilityProbe;
import dev.datrollout.argus.observedetection.probe.ProbeHeuristics;
import dev.datrollout.argus.observedetection.scoring.ScoreAggregator;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
public class OpenApiProbe implements CapabilityProbe {

    private static final List<String> PROBE_PATHS = List.of("/v3/api-docs", "/swagger.json", "/openapi.json");

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public OpenApiProbe(RestClient discoveryRestClient, DiscoveryProperties properties) {
        this.restClient = discoveryRestClient;
    }

    @Override
    public String name() {
        return "OpenApiProbe";
    }

    @Override
    public boolean supports(ProbeTarget target) {
        return !ProbeHeuristics.looksLikeLoki(target) && !ProbeHeuristics.looksLikePrometheus(target);
    }

    @Override
    public DetectionResult probe(ProbeTarget target) {
        ScoreAggregator aggregator = new ScoreAggregator("OPENAPI", target.getBaseUrl());

        for (String path : PROBE_PATHS) {
            try {
                Map<String, Object> body = restClient
                        .get()
                        .uri(target.getBaseUrl() + path)
                        .retrieve()
                        .body(MAP_TYPE);
                if (isValidOpenApi(body)) {
                    aggregator.addSignal(100, "Valid OpenAPI schema at " + path);
                    aggregator.addCapability(Capability.OPENAPI_DISCOVERY);
                    break;
                }
            } catch (RestClientException e) {
                log.debug("OpenApiProbe {} failed for {}: {}", path, target.getBaseUrl(), e.getMessage());
            }
        }

        DetectionResult result = aggregator.build();
        log.info(
                "OpenApiProbe target={} score={} confidence={}",
                target.getBaseUrl(),
                result.getConfidenceScore(),
                result.getConfidenceLevel());
        return result;
    }

    private boolean isValidOpenApi(Map<String, Object> body) {
        return body != null
                && (body.containsKey("openapi")
                        || body.containsKey("swagger")
                        || (body.containsKey("info") && body.containsKey("paths")));
    }
}

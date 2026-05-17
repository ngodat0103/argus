package dev.datrollout.argus.observedetection.probe.loki;

import dev.datrollout.argus.observedetection.config.DiscoveryProperties;
import dev.datrollout.argus.observedetection.model.Capability;
import dev.datrollout.argus.observedetection.model.DetectionResult;
import dev.datrollout.argus.observedetection.model.ProbeTarget;
import dev.datrollout.argus.observedetection.probe.CapabilityProbe;
import dev.datrollout.argus.observedetection.probe.ProbeHeuristics;
import dev.datrollout.argus.observedetection.scoring.ScoreAggregator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class LokiProbe implements CapabilityProbe {

    private static final Set<String> LOKI_IMAGES = Set.of("loki", "grafana/loki");

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public LokiProbe(RestClient discoveryRestClient, DiscoveryProperties properties) {
        this.restClient = discoveryRestClient;
    }

    @Override
    public String name() { return "LokiProbe"; }

    @Override
    public boolean supports(ProbeTarget target) {
        return ProbeHeuristics.looksLikeLoki(target);
    }

    @Override
    public DetectionResult probe(ProbeTarget target) {
        ScoreAggregator aggregator = new ScoreAggregator("LOKI", target.getBaseUrl());

        applyWeakSignals(aggregator, target);
        applyMediumSignals(aggregator, target);
        probeLabels(aggregator, target.getBaseUrl());
        probeQueryRange(aggregator, target.getBaseUrl());
        probeReadiness(aggregator, target.getBaseUrl());

        DetectionResult result = aggregator.build();
        log.info("LokiProbe target={} score={} confidence={}",
                target.getBaseUrl(), result.getConfidenceScore(), result.getConfidenceLevel());
        return result;
    }

    private void applyWeakSignals(ScoreAggregator aggregator, ProbeTarget target) {
        Map<String, String> labels = target.getLabels() != null ? target.getLabels() : Map.of();
        boolean match = labels.containsKey("loki") || labels.containsValue("loki")
                || labels.containsKey("logging") || labels.containsValue("logging");
        if (match) aggregator.addSignal(5, "Label contains loki/logging keyword");
    }

    private void applyMediumSignals(ScoreAggregator aggregator, ProbeTarget target) {
        List<String> images = target.getContainerImages() != null ? target.getContainerImages() : List.of();
        boolean match = images.stream()
                .anyMatch(img -> LOKI_IMAGES.stream().anyMatch(img.toLowerCase()::contains));
        if (match) aggregator.addSignal(20, "Container image matches Loki-compatible image");
    }

    private void probeLabels(ScoreAggregator aggregator, URI base) {
        try {
            Map<String, Object> body = restClient.get()
                    .uri(base + "/loki/api/v1/labels")
                    .retrieve()
                    .body(MAP_TYPE);
            if (isValidLoki(body)) {
                aggregator.addSignal(100, "Valid Loki API schema at /loki/api/v1/labels");
                aggregator.addCapability(Capability.LABEL_DISCOVERY);
                aggregator.addCapability(Capability.LOGQL_QUERY);
            }
        } catch (RestClientException e) {
            log.debug("LokiProbe labels failed for {}: {}", base, e.getMessage());
        }
    }

    private void probeQueryRange(ScoreAggregator aggregator, URI base) {
        try {
            Map<String, Object> body = restClient.get()
                    .uri(base + "/loki/api/v1/query_range")
                    .retrieve()
                    .body(MAP_TYPE);
            if (isValidLoki(body)) {
                aggregator.addSignal(100, "Successful LogQL query at /loki/api/v1/query_range");
                aggregator.addCapability(Capability.LOGQL_QUERY);
                aggregator.addCapability(Capability.LOG_STREAMING);
            }
        } catch (RestClientException e) {
            log.debug("LokiProbe query_range failed for {}: {}", base, e.getMessage());
        }
    }

    private void probeReadiness(ScoreAggregator aggregator, URI base) {
        try {
            String body = restClient.get()
                    .uri(base + "/ready")
                    .retrieve()
                    .body(String.class);
            if (body != null && body.toLowerCase().contains("ready")) {
                aggregator.addSignal(15, "Loki /ready endpoint responded");
                aggregator.addCapability(Capability.METRICS_ENDPOINT);
            }
        } catch (RestClientException e) {
            log.debug("LokiProbe /ready failed for {}: {}", base, e.getMessage());
        }
    }

    private boolean isValidLoki(Map<String, Object> body) {
        return body != null && "success".equals(body.get("status")) && body.containsKey("data");
    }
}

package dev.datrollout.argus.observedetection.probe.prometheus;

import dev.datrollout.argus.observedetection.config.DiscoveryProperties;
import dev.datrollout.argus.observedetection.model.Capability;
import dev.datrollout.argus.observedetection.model.DetectionResult;
import dev.datrollout.argus.observedetection.model.ProbeTarget;
import dev.datrollout.argus.observedetection.probe.CapabilityProbe;
import dev.datrollout.argus.observedetection.probe.ProbeHeuristics;
import dev.datrollout.argus.observedetection.scoring.ScoreAggregator;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Slf4j
@Component
public class PrometheusProbe implements CapabilityProbe {

    private static final Set<String> PROMETHEUS_IMAGES = Set.of("prometheus", "mimir", "thanos", "victoriametrics");

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final RestClient restClient;

    public PrometheusProbe(RestClient discoveryRestClient, DiscoveryProperties properties) {
        this.restClient = discoveryRestClient;
    }

    @Override
    public String name() {
        return "PrometheusProbe";
    }

    @Override
    public boolean supports(ProbeTarget target) {
        return ProbeHeuristics.looksLikePrometheus(target);
    }

    @Override
    public DetectionResult probe(ProbeTarget target) {
        ScoreAggregator aggregator = new ScoreAggregator("PROMETHEUS", target.getBaseUrl());

        applyWeakSignals(aggregator, target);
        applyMediumSignals(aggregator, target);
        probeBuildInfo(aggregator, target.getBaseUrl());
        probeInstantQuery(aggregator, target.getBaseUrl());
        probeRangeQuery(aggregator, target.getBaseUrl());

        DetectionResult result = aggregator.build();
        log.info(
                "PrometheusProbe target={} score={} confidence={}",
                target.getBaseUrl(),
                result.getConfidenceScore(),
                result.getConfidenceLevel());
        return result;
    }

    private void applyWeakSignals(ScoreAggregator aggregator, ProbeTarget target) {
        Map<String, String> labels = target.getLabels() != null ? target.getLabels() : Map.of();
        boolean hasLabel = labels.containsKey("monitoring")
                || labels.containsValue("monitoring")
                || labels.containsKey("prometheus")
                || labels.containsValue("prometheus");
        if (hasLabel) aggregator.addSignal(5, "Label contains monitoring/prometheus keyword");
    }

    private void applyMediumSignals(ScoreAggregator aggregator, ProbeTarget target) {
        List<String> images = target.getContainerImages() != null ? target.getContainerImages() : List.of();
        boolean match =
                images.stream().anyMatch(img -> PROMETHEUS_IMAGES.stream().anyMatch(img.toLowerCase()::contains));
        if (match) aggregator.addSignal(20, "Container image matches Prometheus-compatible image");
    }

    private void probeBuildInfo(ScoreAggregator aggregator, URI base) {
        try {
            Map<String, Object> body = restClient
                    .get()
                    .uri(base + "/api/v1/status/buildinfo")
                    .retrieve()
                    .body(MAP_TYPE);
            if (isSuccess(body)) {
                aggregator.addSignal(80, "Valid Prometheus JSON schema at /api/v1/status/buildinfo");
                aggregator.addCapability(Capability.METRICS_ENDPOINT);
            }
        } catch (RestClientException e) {
            log.debug("PrometheusProbe buildinfo failed for {}: {}", base, e.getMessage());
        }
    }

    private void probeInstantQuery(ScoreAggregator aggregator, URI base) {
        try {
            Map<String, Object> body = restClient
                    .get()
                    .uri(base + "/api/v1/query?query=up")
                    .retrieve()
                    .body(MAP_TYPE);
            if (isSuccess(body) && body.containsKey("data")) {
                aggregator.addSignal(100, "Successful instant query at /api/v1/query");
                aggregator.addCapability(Capability.PROMQL_QUERY);
                aggregator.addCapability(Capability.LABEL_DISCOVERY);
            }
        } catch (RestClientException e) {
            log.debug("PrometheusProbe instant query failed for {}: {}", base, e.getMessage());
        }
    }

    private void probeRangeQuery(ScoreAggregator aggregator, URI base) {
        try {
            Map<String, Object> body = restClient
                    .get()
                    .uri(base + "/api/v1/query_range")
                    .retrieve()
                    .body(MAP_TYPE);
            if (isSuccess(body) && body.containsKey("data")) {
                aggregator.addSignal(80, "Valid range query endpoint /api/v1/query_range");
                aggregator.addCapability(Capability.RANGE_QUERY);
            }
        } catch (RestClientException e) {
            log.debug("PrometheusProbe range query failed for {}: {}", base, e.getMessage());
        }
    }

    private boolean isSuccess(Map<String, Object> body) {
        return body != null && "success".equals(body.get("status"));
    }
}

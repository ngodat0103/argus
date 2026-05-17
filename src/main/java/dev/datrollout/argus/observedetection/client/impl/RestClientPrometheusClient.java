package dev.datrollout.argus.observedetection.client.impl;

import dev.datrollout.argus.observedetection.client.PrometheusClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public class RestClientPrometheusClient implements PrometheusClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final URI endpoint;
    private final RestClient restClient;

    public RestClientPrometheusClient(URI endpoint, RestClient restClient) {
        this.endpoint = endpoint;
        this.restClient = restClient;
    }

    @Override
    public URI getEndpoint() { return endpoint; }

    @Override
    public String clientType() { return "PROMETHEUS"; }

    @Override
    public Map<String, Object> query(String promql) {
        return restClient.get()
                .uri(endpoint + "/api/v1/query?query={q}", promql)
                .retrieve()
                .body(MAP_TYPE);
    }

    @Override
    public Map<String, Object> queryRange(String promql, Instant start, Instant end, Duration step) {
        long stepSec = Math.max(1, step.toSeconds());
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme(endpoint.getScheme())
                        .host(endpoint.getHost())
                        .port(endpoint.getPort())
                        .path("/api/v1/query_range")
                        .queryParam("query", promql)
                        .queryParam("start", start.getEpochSecond())
                        .queryParam("end", end.getEpochSecond())
                        .queryParam("step", stepSec + "s")
                        .build())
                .retrieve()
                .body(MAP_TYPE);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> labels() {
        Map<String, Object> body = restClient.get()
                .uri(endpoint + "/api/v1/labels")
                .retrieve()
                .body(MAP_TYPE);
        if (body != null && body.get("data") instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    @Override
    public Map<String, Object> buildInfo() {
        return restClient.get()
                .uri(endpoint + "/api/v1/status/buildinfo")
                .retrieve()
                .body(MAP_TYPE);
    }
}

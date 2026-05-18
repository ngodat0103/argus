package dev.datrollout.argus.observedetection.client.impl;

import dev.datrollout.argus.observedetection.client.LokiClient;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

public class RestClientLokiClient implements LokiClient {

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final URI endpoint;
    private final RestClient restClient;

    public RestClientLokiClient(URI endpoint, RestClient restClient) {
        this.endpoint = endpoint;
        this.restClient = restClient;
    }

    @Override
    public URI getEndpoint() {
        return endpoint;
    }

    @Override
    public String clientType() {
        return "LOKI";
    }

    @Override
    public Map<String, Object> queryRange(String logql, Instant start, Instant end) {
        return restClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .scheme(endpoint.getScheme())
                        .host(endpoint.getHost())
                        .port(endpoint.getPort())
                        .path("/loki/api/v1/query_range")
                        .queryParam("query", logql)
                        .queryParam("start", start.toEpochMilli() + "000000")
                        .queryParam("end", end.toEpochMilli() + "000000")
                        .build())
                .retrieve()
                .body(MAP_TYPE);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> labels() {
        Map<String, Object> body = restClient
                .get()
                .uri(endpoint + "/loki/api/v1/labels")
                .retrieve()
                .body(MAP_TYPE);
        if (body != null && body.get("data") instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of();
    }

    @Override
    public Map<String, Object> streams(String logql, Instant start, Instant end, int limit) {
        return restClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .scheme(endpoint.getScheme())
                        .host(endpoint.getHost())
                        .port(endpoint.getPort())
                        .path("/loki/api/v1/query_range")
                        .queryParam("query", logql)
                        .queryParam("start", start.toEpochMilli() + "000000")
                        .queryParam("end", end.toEpochMilli() + "000000")
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .body(MAP_TYPE);
    }
}

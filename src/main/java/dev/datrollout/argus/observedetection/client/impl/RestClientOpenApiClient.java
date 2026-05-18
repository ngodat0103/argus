package dev.datrollout.argus.observedetection.client.impl;

import dev.datrollout.argus.observedetection.client.OpenApiClient;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

public class RestClientOpenApiClient implements OpenApiClient {

    private static final List<String> SCHEMA_PATHS = List.of("/v3/api-docs", "/swagger.json", "/openapi.json");

    private static final ParameterizedTypeReference<Map<String, Object>> MAP_TYPE =
            new ParameterizedTypeReference<>() {};

    private final URI endpoint;
    private final RestClient restClient;

    public RestClientOpenApiClient(URI endpoint, RestClient restClient) {
        this.endpoint = endpoint;
        this.restClient = restClient;
    }

    @Override
    public URI getEndpoint() {
        return endpoint;
    }

    @Override
    public String clientType() {
        return "OPENAPI";
    }

    @Override
    public Map<String, Object> getSchema() {
        for (String path : SCHEMA_PATHS) {
            try {
                Map<String, Object> schema =
                        restClient.get().uri(endpoint + path).retrieve().body(MAP_TYPE);
                if (schema != null) return schema;
            } catch (RestClientException ignored) {
            }
        }
        return Map.of();
    }
}

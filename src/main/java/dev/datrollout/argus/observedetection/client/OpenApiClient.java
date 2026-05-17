package dev.datrollout.argus.observedetection.client;

import java.util.Map;

public interface OpenApiClient extends ObservabilityClient {

    Map<String, Object> getSchema();
}

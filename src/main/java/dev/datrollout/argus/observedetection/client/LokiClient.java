package dev.datrollout.argus.observedetection.client;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface LokiClient extends ObservabilityClient {

    Map<String, Object> queryRange(String logql, Instant start, Instant end);

    List<String> labels();

    Map<String, Object> streams(String logql, Instant start, Instant end, int limit);
}

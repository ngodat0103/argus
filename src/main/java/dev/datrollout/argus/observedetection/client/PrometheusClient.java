package dev.datrollout.argus.observedetection.client;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface PrometheusClient extends ObservabilityClient {

    Map<String, Object> query(String promql);

    Map<String, Object> queryRange(String promql, Instant start, Instant end, Duration step);

    List<String> labels();

    Map<String, Object> buildInfo();
}

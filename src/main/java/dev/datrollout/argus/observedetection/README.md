# Observe-Detection Engine

Kubernetes-native heuristic capability detection for observability backends.

At application startup, the engine lists Services in configured namespaces, selects Loki and Prometheus server endpoints, probes them over HTTP (via in-cluster DNS or optional port-forward), scores responses, registers one logical provider per namespace, and builds typed clients for confirmed capabilities.

---

## Architecture

```
CapabilitiesDiscoverer (@PostConstruct, startup only)
    ↓ lists Services, filters via ProbeHeuristics
ProbeTargetExtractor  →  ProbeTarget (cluster DNS URL + ports)
    ↓
ProbeOrchestrator
    ↓ optional PortForwardManager (local dev)
    ↓ parallel on probeExecutor (max 10 platform threads)
[PrometheusProbe] [LokiProbe] [OpenApiProbe*]
    ↓ DetectionResult per probe (score + capabilities + evidence)
DetectionResultAggregator.aggregateByProvider()
    ↓ one result per provider type (never cross-merged)
CapabilityRegistry  (key: PROVIDER|namespace, cluster endpoint stored)
    ↓ CapabilityDetectedEvent
ClientBuilderListener  →  [PrometheusClientFactory] [LokiClientFactory] [OpenApiClientFactory]
    ↓
ClientRegistry  (key: clientType — one client per provider type)
```

\* `OpenApiProbe` is registered as a Spring bean but is **not** dispatched during normal discovery, because `CapabilitiesDiscoverer` only probes targets where `ProbeHeuristics.isObservabilityTarget()` is true.

---

## Core Principle

The engine detects **capabilities**, not chart product names.

Probes send real HTTP requests and score responses. A service running Thanos, Mimir, or VictoriaMetrics can be detected as exposing `PROMQL_QUERY` when `/api/v1/query` returns a valid Prometheus-style payload.

**Target selection** uses `ProbeHeuristics` so unrelated chart services (Grafana, Alertmanager, kube-state-metrics, etc.) are not probed. **Registration** deduplicates alias Services (e.g. `loki` vs `loki-headless`) to a single entry per provider per namespace.

---

## Package Structure

```
observedetection/
├── model/
│   ├── Capability.java
│   ├── ConfidenceLevel.java
│   ├── DetectionResult.java      # withEndpoint() for stable cluster URI after port-forward
│   └── ProbeTarget.java          # SERVICE source; key = namespace/serviceName
│
├── config/
│   ├── DiscoveryProperties.java  # @ConfigurationProperties("discovery")
│   └── DiscoveryConfiguration.java
│
├── cache/
│   └── ProbeResultCache.java     # TTL cache keyed by cluster endpoint URI
│
├── scoring/
│   └── ScoreAggregator.java
│
├── probe/
│   ├── CapabilityProbe.java
│   ├── ProbeFactory.java
│   ├── DefaultProbeFactory.java
│   ├── ProbeHeuristics.java      # target selection, port choice, provider dedup keys
│   ├── prometheus/PrometheusProbe.java
│   ├── loki/LokiProbe.java
│   └── openapi/OpenApiProbe.java
│
├── aggregation/
│   └── DetectionResultAggregator.java  # aggregateByProvider() — no cross-provider merge
│
├── registry/
│   ├── CapabilityRegistry.java
│   └── InMemoryCapabilityRegistry.java
│
├── orchestration/
│   └── ProbeOrchestrator.java
│
├── portforward/
│   └── PortForwardManager.java   # Fabric8 LocalPortForward (no kubectl binary)
│
├── watcher/
│   ├── CapabilitiesDiscoverer.java   # startup Service scan
│   └── ProbeTargetExtractor.java     # Service → ProbeTarget
│
└── client/
    ├── ObservabilityClient.java
    ├── PrometheusClient.java
    ├── LokiClient.java
    ├── OpenApiClient.java
    ├── impl/ ...
    ├── factory/ ...
    ├── registry/
    │   ├── ClientRegistry.java
    │   └── InMemoryClientRegistry.java
    └── event/
        ├── CapabilityDetectedEvent.java
        └── ClientBuilderListener.java
```

---

## Discovery Flow

### 1. Startup scan (`CapabilitiesDiscoverer`)

Triggered once via `@PostConstruct`. Controlled by `discovery.watcher.enabled` (default `true`; set `false` in tests).

For each Service in `discovery.namespaces` (empty = all namespaces):

| Filter | Reason |
|--------|--------|
| Headless (`clusterIP: None` or name ends with `-headless`) | Alias of a main Service; skipped at source |
| Not `ProbeHeuristics.isObservabilityTarget()` | Skips Grafana, Alertmanager, operator, canary, etc. |
| Duplicate `providerKey` | One probe per `LOKI\|ns` or `PROMETHEUS\|ns` |

### 2. Target extraction (`ProbeTargetExtractor`)

Builds a cluster DNS URL: `http://{service}.{namespace}.svc.cluster.local:{port}`.

Port selection (`ProbeHeuristics.selectProbePort`):

| Workload | Preferred port |
|----------|----------------|
| Prometheus server (`prometheus`, `*-prometheus`) | **9090** before 8080 (reloader) |
| Loki server (`loki`) | **3100** |
| Other | 9090 → 3100 → 8080 → 80 → 443 → first listed |

### 3. Probing (`ProbeOrchestrator`)

- **`discovery.port-forward.enabled: true`** (typical local dev): opens a short-lived tunnel, probes `localhost:{ephemeral}`, registers the **cluster** endpoint on the result.
- **`false`** (in-cluster): probes the cluster DNS URL directly.
- Probes for one target run in parallel; each provider result is registered separately.
- `ProbeResultCache` skips re-probing the same cluster endpoint for the cache TTL.

### 4. Registry deduplication (`InMemoryCapabilityRegistry`)

| Key | Example |
|-----|---------|
| `{providerType}\|{namespace}` | `LOKI\|loki`, `PROMETHEUS\|kube-prometheus-stack` |

If a second Service maps to the same key (e.g. headless alias probed before the filter applied elsewhere), the entry with the higher score wins; on a tie, the non-headless hostname is preferred.

---

## Target Heuristics (`ProbeHeuristics`)

### Loki server

- Service name normalizes to `loki` (`loki-headless` → `loki`)
- Does **not** match `loki-canary`, `loki-gateway`, etc.

### Prometheus server

- Service name is `prometheus` or ends with `-prometheus` (e.g. `kube-prometheus-stack-prometheus`)
- Excludes companions: alertmanager, grafana, operator, kube-state-metrics, node-exporter, thanos
- Does **not** treat namespace `kube-prometheus-stack` as a match (substring `prometheus` is not used)

### Provider dedup key

`LOKI|{namespace}` or `PROMETHEUS|{namespace}` — only for the server services above.

---

## Capabilities

| Capability | Meaning |
|------------|---------|
| `PROMQL_QUERY` | Instant PromQL query (`/api/v1/query`) |
| `RANGE_QUERY` | Range PromQL query (`/api/v1/query_range`) |
| `LOGQL_QUERY` | LogQL query (`/loki/api/v1/query_range`) |
| `LOG_STREAMING` | Log stream results |
| `METRICS_ENDPOINT` | Metrics or readiness endpoint |
| `LABEL_DISCOVERY` | Label enumeration API |
| `OPENAPI_DISCOVERY` | OpenAPI/Swagger schema |

---

## Confidence Scoring

| Score | Level | Action |
|-------|-------|--------|
| ≥ 100 | `CONFIRMED` | Registered + client built |
| ≥ 70 | `PROBABLE` | Registered + client built |
| ≥ 40 | `POSSIBLE` | Registered, no client built |
| < 40 | `IGNORE` | Discarded |

### Prometheus scoring

| Signal | Score |
|--------|-------|
| `GET /api/v1/status/buildinfo` → `status=success` | +80 |
| `GET /api/v1/query?query=up` → `status=success` + `data` | +100 |
| `GET /api/v1/query_range` → `status=success` + `data` | +80 |
| Container image matches prometheus/mimir/thanos/victoriametrics | +20 |
| Label contains monitoring/prometheus | +5 |

Capabilities: `PROMQL_QUERY`, `RANGE_QUERY`, `METRICS_ENDPOINT`, `LABEL_DISCOVERY`

### Loki scoring

| Signal | Score |
|--------|-------|
| `GET /loki/api/v1/labels` → valid Loki schema | +100 |
| `GET /loki/api/v1/query_range` → valid Loki schema | +100 |
| `GET /ready` → body contains "ready" | +15 |
| Container image contains loki | +20 |
| Label contains loki/logging | +5 |

Capabilities: `LOGQL_QUERY`, `LOG_STREAMING`, `LABEL_DISCOVERY`, `METRICS_ENDPOINT`

### OpenAPI scoring

| Path | Score |
|------|-------|
| `/v3/api-docs`, `/swagger.json`, or `/openapi.json` | +100 |

Capability: `OPENAPI_DISCOVERY` (only when this probe is invoked)

---

## Configuration

```yaml
discovery:
  namespaces:              # Only these namespaces; empty = all
    - kube-prometheus-stack
    - loki
  probe:
    timeout: 3s            # Per-probe Future.get timeout
    retry-count: 2         # Reserved (not wired yet)
  security:
    allowed-cidrs:         # Reserved (not wired yet)
      - 10.0.0.0/8
      - 172.16.0.0/12
      - 192.168.0.0/16
  cache:
    ttl: 5m                # Skip re-probing same cluster endpoint
  port-forward:
    enabled: true          # true for local dev outside the cluster
  watcher:
    enabled: true          # false disables CapabilitiesDiscoverer (e.g. tests)
```

---

## Typed Clients

On `CONFIRMED` or `PROBABLE` registration, `ClientBuilderListener` builds a client and stores it in `ClientRegistry` keyed by `clientType()` (one Prometheus client, one Loki client, etc.). Endpoints are **cluster DNS URLs**, not ephemeral localhost ports.

```java
@Autowired ClientRegistry clientRegistry;

PrometheusClient prom = clientRegistry.findByType(PrometheusClient.class).getFirst();
Map<String, Object> result = prom.query("up");

LokiClient loki = clientRegistry.findByType(LokiClient.class).getFirst();
Map<String, Object> logs = loki.queryRange("{app=\"myapp\"}", start, end);
```

---

## Concurrency Model

- **Probe execution:** synchronous `RestClient` calls on a fixed platform thread pool (`probeExecutor`, max 10 threads). Per-target probes are submitted with `ExecutorService.submit()` and collected via `Future.get(timeout)`.
- **Discovery:** runs on the main thread at startup; no background informers or debounce schedulers.

---

## Security Notes

- **Namespace filtering:** only `discovery.namespaces` are scanned (empty = all).
- **Read-only:** no Kubernetes mutations; listing and port-forward only.
- **`discovery.security.allowed-cidrs`:** defined in `DiscoveryProperties` but not enforced by the engine yet.

---

## Metrics (Micrometer)

| Metric | Type | Description |
|--------|------|-------------|
| `argus.probes.total` | Counter | Probe executions |
| `argus.probes.failed` | Counter | Probe exceptions or timeouts |
| `argus.probe.latency` | Timer | Per-target orchestration latency |
| `argus.capabilities.detected` | Counter | Registrations above threshold |
| `argus.clients.built` | Counter | Typed clients constructed |

Exposed via Actuator at `/actuator/prometheus`.

---

## Extending with a New Probe

1. Implement `CapabilityProbe` and annotate with `@Component`.
2. Implement `supports(ProbeTarget)` — return `true` only for relevant targets.
3. Optionally add selection logic to `ProbeHeuristics` and `CapabilitiesDiscoverer` if the probe should run at startup.
4. For a typed client: implement `ClientFactory` + `ObservabilityClient`.

```java
@Component
public class TempoProbe implements CapabilityProbe {

    @Override public String name() { return "TempoProbe"; }

    @Override
    public boolean supports(ProbeTarget target) {
        return ProbeHeuristics.isTempoServerService(target.getServiceName()); // add helper
    }

    @Override
    public DetectionResult probe(ProbeTarget target) {
        ScoreAggregator agg = new ScoreAggregator("TEMPO", target.getBaseUrl());
        // probe /api/echo, /ready, etc.
        return agg.build();
    }
}
```

No changes to `ProbeOrchestrator` or `DetectionResultAggregator` are required beyond heuristics/discovery wiring.

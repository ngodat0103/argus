package dev.datrollout.argus.observedetection.orchestration;

import dev.datrollout.argus.observedetection.aggregation.DetectionResultAggregator;
import dev.datrollout.argus.observedetection.cache.ProbeResultCache;
import dev.datrollout.argus.observedetection.config.DiscoveryProperties;
import dev.datrollout.argus.observedetection.model.ConfidenceLevel;
import dev.datrollout.argus.observedetection.model.DetectionResult;
import dev.datrollout.argus.observedetection.model.ProbeTarget;
import dev.datrollout.argus.observedetection.portforward.PortForwardManager;
import dev.datrollout.argus.observedetection.probe.CapabilityProbe;
import dev.datrollout.argus.observedetection.probe.ProbeFactory;
import dev.datrollout.argus.observedetection.registry.CapabilityRegistry;
import io.fabric8.kubernetes.client.LocalPortForward;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class ProbeOrchestrator {

    private final ProbeFactory probeFactory;
    private final DetectionResultAggregator aggregator;
    private final CapabilityRegistry capabilityRegistry;
    private final ProbeResultCache cache;
    private final ExecutorService probeExecutor;
    private final PortForwardManager portForwardManager;
    private final boolean portForwardEnabled;
    private final Duration probeTimeout;
    private final Counter probesTotalCounter;
    private final Counter probesFailedCounter;
    private final Timer probeLatencyTimer;

    public ProbeOrchestrator(ProbeFactory probeFactory,
                             DetectionResultAggregator aggregator,
                             CapabilityRegistry capabilityRegistry,
                             ProbeResultCache cache,
                             @Qualifier("probeExecutor") ExecutorService probeExecutor,
                             PortForwardManager portForwardManager,
                             DiscoveryProperties properties,
                             MeterRegistry meterRegistry) {
        this.probeFactory = probeFactory;
        this.aggregator = aggregator;
        this.capabilityRegistry = capabilityRegistry;
        this.cache = cache;
        this.probeExecutor = probeExecutor;
        this.portForwardManager = portForwardManager;
        this.portForwardEnabled = properties.portForward().enabled();
        this.probeTimeout = properties.probe().timeout();
        this.probesTotalCounter = Counter.builder("argus.probes.total")
                .description("Total probe executions")
                .register(meterRegistry);
        this.probesFailedCounter = Counter.builder("argus.probes.failed")
                .description("Failed probe executions")
                .register(meterRegistry);
        this.probeLatencyTimer = Timer.builder("argus.probe.latency")
                .description("Probe execution latency")
                .register(meterRegistry);
    }

    public void orchestrate(ProbeTarget target) {
        URI clusterEndpoint = target.getBaseUrl();
        if (cache.contains(clusterEndpoint)) {
            log.debug("Cache hit for target={}, skipping", target.key());
            return;
        }

        List<CapabilityProbe> probes = probeFactory.probesFor(target);
        if (probes.isEmpty()) {
            log.debug("No applicable probes for target={}", target.key());
            return;
        }

        if (portForwardEnabled) {
            probeViaPortForward(target, probes);
        } else {
            runAndRegister(target, clusterEndpoint, probes);
        }
    }

    /**
     * Opens a {@code kubectl port-forward} tunnel for {@code target}, rewrites its
     * {@code baseUrl} to {@code localhost:localPort}, runs all probes, then closes the tunnel.
     */
    private void probeViaPortForward(ProbeTarget target, List<CapabilityProbe> probes) {
        int remotePort = target.getBaseUrl().getPort();
        if (remotePort <= 0) remotePort = 80;

        URI clusterEndpoint = target.getBaseUrl();
        try (LocalPortForward localPortForward = portForwardManager.open(target, remotePort)) {
            URI tunnelBaseUrl = URI.create("http://localhost:" + localPortForward.getLocalPort());
            ProbeTarget tunnelTarget = target.withBaseUrl(tunnelBaseUrl);
            runAndRegister(tunnelTarget, clusterEndpoint, probes);
        } catch (Exception e) {
            log.warn("Port-forward failed for target={}: {}", target.key(), e.getMessage());
        }
    }

    private void runAndRegister(ProbeTarget probeTarget, URI registerEndpoint, List<CapabilityProbe> probes) {
        Timer.Sample sample = Timer.start();
        List<DetectionResult> results = runProbesInParallel(probes, probeTarget);
        sample.stop(probeLatencyTimer);

        if (results.isEmpty()) return;

        boolean registered = false;
        for (DetectionResult result : aggregator.aggregateByProvider(results)) {
            DetectionResult forRegistry = result.withEndpoint(registerEndpoint);
            if (forRegistry.getConfidenceLevel() == ConfidenceLevel.IGNORE) {
                log.debug("Probe result below threshold for target={} provider={} score={}",
                        probeTarget.key(), forRegistry.getProviderType(), forRegistry.getConfidenceScore());
                continue;
            }
            capabilityRegistry.register(forRegistry);
            registered = true;
        }

        if (registered) {
            cache.put(registerEndpoint, results.getFirst().withEndpoint(registerEndpoint));
        }
    }

    private List<DetectionResult> runProbesInParallel(List<CapabilityProbe> probes, ProbeTarget target) {
        List<Future<DetectionResult>> futures = probes.stream()
                .map(probe -> probeExecutor.submit(() -> {
                    probesTotalCounter.increment();
                    return probe.probe(target);
                }))
                .toList();

        List<DetectionResult> results = new ArrayList<>();
        long timeoutMs = probeTimeout.toMillis();

        for (Future<DetectionResult> future : futures) {
            try {
                DetectionResult result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                if (result != null) results.add(result);
            } catch (Exception e) {
                probesFailedCounter.increment();
                log.warn("Probe failed or timed out for target={}: {}", target.key(), e.getMessage());
            }
        }
        return results;
    }
}

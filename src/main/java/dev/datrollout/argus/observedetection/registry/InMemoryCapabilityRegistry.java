package dev.datrollout.argus.observedetection.registry;

import dev.datrollout.argus.observedetection.client.event.CapabilityDetectedEvent;
import dev.datrollout.argus.observedetection.model.Capability;
import dev.datrollout.argus.observedetection.model.ConfidenceLevel;
import dev.datrollout.argus.observedetection.model.DetectionResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class InMemoryCapabilityRegistry implements CapabilityRegistry {

    private final ConcurrentHashMap<String, DetectionResult> store = new ConcurrentHashMap<>();
    private final ApplicationEventPublisher eventPublisher;
    private final Counter detectedCounter;

    public InMemoryCapabilityRegistry(ApplicationEventPublisher eventPublisher,
                                      MeterRegistry meterRegistry) {
        this.eventPublisher = eventPublisher;
        this.detectedCounter = Counter.builder("argus.capabilities.detected")
                .description("Total capability detection registrations")
                .register(meterRegistry);
    }

    @Override
    public void register(DetectionResult result) {
        if (result.getConfidenceLevel() == ConfidenceLevel.IGNORE) {
            log.debug("Ignoring result below threshold endpoint={} score={}",
                    result.getEndpoint(), result.getConfidenceScore());
            return;
        }

        String key = registryKey(result);
        DetectionResult existing = store.get(key);
        if (existing != null && !shouldReplace(existing, result)) {
            log.debug("Skipping duplicate provider={} existing={} candidate={}",
                    result.getProviderType(), existing.getEndpoint(), result.getEndpoint());
            return;
        }

        store.put(key, result);
        detectedCounter.increment();
        log.info("Registered capabilities endpoint={} provider={} confidence={} score={} caps={}",
                result.getEndpoint(), result.getProviderType(),
                result.getConfidenceLevel(), result.getConfidenceScore(), result.getCapabilities());
        eventPublisher.publishEvent(new CapabilityDetectedEvent(this, result));
    }

    @Override
    public List<DetectionResult> findByCapability(Capability capability) {
        return store.values().stream().filter(r -> r.hasCapability(capability)).toList();
    }

    @Override
    public Optional<DetectionResult> findByEndpoint(URI endpoint) {
        return store.values().stream()
                .filter(r -> endpoint.equals(r.getEndpoint()))
                .findFirst();
    }

    private static String registryKey(DetectionResult result) {
        return result.getProviderType() + "|" + namespaceFromEndpoint(result.getEndpoint());
    }

    private static String namespaceFromEndpoint(URI endpoint) {
        String host = endpoint.getHost();
        if (host == null || host.isBlank()) return "";
        String[] labels = host.split("\\.");
        return labels.length >= 2 ? labels[1] : "";
    }

    private static boolean shouldReplace(DetectionResult existing, DetectionResult candidate) {
        if (candidate.getConfidenceScore() > existing.getConfidenceScore()) return true;
        if (candidate.getConfidenceScore() < existing.getConfidenceScore()) return false;
        return preferEndpoint(candidate.getEndpoint(), existing.getEndpoint());
    }

    /** Prefer the standard cluster Service DNS name over headless variants. */
    private static boolean preferEndpoint(URI candidate, URI existing) {
        boolean candidateHeadless = isHeadlessHost(candidate);
        boolean existingHeadless = isHeadlessHost(existing);
        if (candidateHeadless != existingHeadless) return !candidateHeadless;
        return false;
    }

    private static boolean isHeadlessHost(URI uri) {
        String host = uri.getHost();
        return host != null && host.contains("-headless");
    }

    @Override
    public List<DetectionResult> getAll() {
        return List.copyOf(store.values());
    }
}

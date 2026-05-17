package dev.datrollout.argus.observedetection.cache;

import dev.datrollout.argus.observedetection.config.DiscoveryProperties;
import dev.datrollout.argus.observedetection.model.DetectionResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ProbeResultCache {

    private final Duration ttl;
    private final Clock clock;
    private final ConcurrentHashMap<URI, CachedEntry> store = new ConcurrentHashMap<>();

    @Autowired
    public ProbeResultCache(DiscoveryProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public ProbeResultCache(DiscoveryProperties properties, Clock clock) {
        this.ttl = properties.cache().ttl();
        this.clock = clock;
    }

    public void put(URI endpoint, DetectionResult result) {
        store.put(endpoint, new CachedEntry(result, clock.instant().plus(ttl)));
    }

    public Optional<DetectionResult> get(URI endpoint) {
        CachedEntry entry = store.get(endpoint);
        if (entry == null) return Optional.empty();
        if (clock.instant().isAfter(entry.expiresAt())) {
            store.remove(endpoint);
            return Optional.empty();
        }
        return Optional.of(entry.result());
    }

    public boolean contains(URI endpoint) {
        return get(endpoint).isPresent();
    }

    public void invalidate(URI endpoint) {
        store.remove(endpoint);
    }

    public void evictExpired() {
        Instant now = clock.instant();
        store.entrySet().removeIf(e -> now.isAfter(e.getValue().expiresAt()));
    }

    public record CachedEntry(DetectionResult result, Instant expiresAt) {}
}

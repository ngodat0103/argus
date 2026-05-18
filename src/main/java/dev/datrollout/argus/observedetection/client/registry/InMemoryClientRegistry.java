package dev.datrollout.argus.observedetection.client.registry;

import dev.datrollout.argus.observedetection.client.ObservabilityClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class InMemoryClientRegistry implements ClientRegistry {

    private final ConcurrentHashMap<String, ObservabilityClient> store = new ConcurrentHashMap<>();
    private final Counter builtCounter;

    public InMemoryClientRegistry(MeterRegistry meterRegistry) {
        this.builtCounter = Counter.builder("argus.clients.built")
                .description("Total observability clients built")
                .register(meterRegistry);
    }

    @Override
    public void register(ObservabilityClient client) {
        store.put(client.clientType(), client);
        builtCounter.increment();
        log.info("Registered client type={} endpoint={}", client.clientType(), client.getEndpoint());
    }

    @Override
    public Optional<ObservabilityClient> findByEndpoint(URI endpoint) {
        return store.values().stream()
                .filter(c -> endpoint.equals(c.getEndpoint()))
                .findFirst();
    }

    @Override
    public <T extends ObservabilityClient> List<T> findByType(Class<T> type) {
        return store.values().stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Override
    public List<ObservabilityClient> getAll() {
        return List.copyOf(store.values());
    }
}

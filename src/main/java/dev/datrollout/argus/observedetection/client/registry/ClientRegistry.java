package dev.datrollout.argus.observedetection.client.registry;

import dev.datrollout.argus.observedetection.client.ObservabilityClient;

import java.net.URI;
import java.util.List;
import java.util.Optional;

public interface ClientRegistry {

    void register(ObservabilityClient client);

    Optional<ObservabilityClient> findByEndpoint(URI endpoint);

    <T extends ObservabilityClient> List<T> findByType(Class<T> type);

    List<ObservabilityClient> getAll();
}

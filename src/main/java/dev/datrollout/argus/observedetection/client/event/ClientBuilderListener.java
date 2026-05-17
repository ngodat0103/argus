package dev.datrollout.argus.observedetection.client.event;

import dev.datrollout.argus.observedetection.client.ObservabilityClient;
import dev.datrollout.argus.observedetection.client.factory.ClientFactory;
import dev.datrollout.argus.observedetection.client.registry.ClientRegistry;
import dev.datrollout.argus.observedetection.model.ConfidenceLevel;
import dev.datrollout.argus.observedetection.model.DetectionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class ClientBuilderListener {

    private final List<ClientFactory> clientFactories;
    private final ClientRegistry clientRegistry;

    public ClientBuilderListener(List<ClientFactory> clientFactories, ClientRegistry clientRegistry) {
        this.clientFactories = List.copyOf(clientFactories);
        this.clientRegistry = clientRegistry;
    }

    @EventListener
    public void onCapabilityDetected(CapabilityDetectedEvent event) {
        DetectionResult result = event.getResult();

        if (result.getConfidenceLevel() == ConfidenceLevel.POSSIBLE
                || result.getConfidenceLevel() == ConfidenceLevel.IGNORE) {
            log.debug("Skipping client build for endpoint={} confidence={}",
                    result.getEndpoint(), result.getConfidenceLevel());
            return;
        }

        for (ClientFactory factory : clientFactories) {
            if (factory.supports(result)) {
                try {
                    ObservabilityClient client = factory.build(result);
                    clientRegistry.register(client);
                    log.info("Built client type={} endpoint={} confidence={}",
                            client.clientType(), result.getEndpoint(), result.getConfidenceLevel());
                } catch (Exception e) {
                    log.error("Failed to build client via {} for endpoint={}",
                            factory.getClass().getSimpleName(), result.getEndpoint(), e);
                }
            }
        }
    }
}

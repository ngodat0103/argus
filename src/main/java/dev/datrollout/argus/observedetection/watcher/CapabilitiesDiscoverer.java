package dev.datrollout.argus.observedetection.watcher;

import dev.datrollout.argus.observedetection.config.DiscoveryProperties;
import dev.datrollout.argus.observedetection.model.Capability;
import dev.datrollout.argus.observedetection.model.DetectionResult;
import dev.datrollout.argus.observedetection.model.ProbeTarget;
import dev.datrollout.argus.observedetection.orchestration.ProbeOrchestrator;
import dev.datrollout.argus.observedetection.probe.ProbeHeuristics;
import dev.datrollout.argus.observedetection.registry.CapabilityRegistry;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.annotation.PostConstruct;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "discovery.watcher.enabled", havingValue = "true", matchIfMissing = true)
public class CapabilitiesDiscoverer {

    private final KubernetesClient kubernetesClient;
    private final ProbeOrchestrator probeOrchestrator;
    private final ProbeTargetExtractor extractor;
    private final List<String> watchedNamespaces;
    private final CapabilityRegistry capabilityRegistry;

    public CapabilitiesDiscoverer(
            KubernetesClient kubernetesClient,
            ProbeOrchestrator probeOrchestrator,
            ProbeTargetExtractor extractor,
            CapabilityRegistry capabilityRegistry,
            DiscoveryProperties properties) {
        this.kubernetesClient = kubernetesClient;
        this.probeOrchestrator = probeOrchestrator;
        this.capabilityRegistry = capabilityRegistry;
        this.extractor = extractor;
        this.watchedNamespaces = properties.namespaces();
    }

    @PostConstruct
    public void discoverAtStartup() {
        Set<String> dispatchedServices = new HashSet<>();
        Set<String> dispatchedProviders = new HashSet<>();
        int servicesScanned = 0;
        int targetsProbed = 0;

        if (watchedNamespaces.isEmpty()) {
            for (Service service :
                    kubernetesClient.services().inAnyNamespace().list().getItems()) {
                servicesScanned++;
                targetsProbed += dispatchService(service, dispatchedServices, dispatchedProviders);
            }
        } else {
            for (String ns : watchedNamespaces) {
                for (Service service :
                        kubernetesClient.services().inNamespace(ns).list().getItems()) {
                    servicesScanned++;
                    targetsProbed += dispatchService(service, dispatchedServices, dispatchedProviders);
                }
            }
        }

        List<DetectionResult> detectionResults = capabilityRegistry.getAll().stream()
                .sorted(Comparator.comparing(DetectionResult::getProviderType))
                .toList();

        log.info(
                "Capability discovery scan complete: namespaces={}, servicesScanned={}, targetsProbed={}",
                watchedNamespaces.isEmpty() ? "ALL" : watchedNamespaces,
                servicesScanned,
                targetsProbed);
        logDiscoverySummary(detectionResults);
    }

    private void logDiscoverySummary(List<DetectionResult> results) {
        if (results.isEmpty()) {
            log.warn(
                    "No observability providers detected in cluster (namespaces={})",
                    watchedNamespaces.isEmpty() ? "ALL" : watchedNamespaces);
            return;
        }

        Set<Capability> clusterCapabilities = EnumSet.noneOf(Capability.class);
        log.info("Cluster observability providers ({}):", results.size());
        for (DetectionResult result : results) {
            clusterCapabilities.addAll(result.getCapabilities());
            log.info(
                    "  - provider={} endpoint={} confidence={} score={} capabilities={}",
                    result.getProviderType(),
                    result.getEndpoint(),
                    result.getConfidenceLevel(),
                    result.getConfidenceScore(),
                    formatCapabilities(result.getCapabilities()));
        }

        log.info(
                "Cluster-supported capabilities ({}): {}",
                clusterCapabilities.size(),
                formatCapabilities(clusterCapabilities));
    }

    private static String formatCapabilities(Set<Capability> capabilities) {
        if (capabilities == null || capabilities.isEmpty()) {
            return "[]";
        }
        return capabilities.stream()
                .sorted(Comparator.comparing(Enum::name))
                .map(Capability::name)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private int dispatchService(Service service, Set<String> dispatchedServices, Set<String> dispatchedProviders) {
        if (isHeadlessService(service)) {
            log.debug(
                    "Skipping headless service {}/{}",
                    service.getMetadata().getNamespace(),
                    service.getMetadata().getName());
            return 0;
        }

        return extractor
                .fromService(service)
                .filter(ProbeHeuristics::isObservabilityTarget)
                .filter(target -> shouldDispatch(target, dispatchedServices, dispatchedProviders))
                .map(target -> {
                    log.debug("Dispatching probe for service target={}", target.key());
                    probeOrchestrator.orchestrate(target);
                    return 1;
                })
                .orElse(0);
    }

    private boolean shouldDispatch(
            ProbeTarget target, Set<String> dispatchedServices, Set<String> dispatchedProviders) {
        var providerKey = ProbeHeuristics.providerKey(target);
        if (providerKey.isPresent() && !dispatchedProviders.add(providerKey.get())) {
            log.debug("Skipping duplicate provider {} for target={}", providerKey.get(), target.key());
            return false;
        }
        return dispatchedServices.add(target.key());
    }

    private boolean isHeadlessService(Service service) {
        String name = service.getMetadata().getName();
        if (name != null && name.endsWith("-headless")) {
            return true;
        }
        return service.getSpec() != null && "None".equals(service.getSpec().getClusterIP());
    }
}

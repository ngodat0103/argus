package dev.datrollout.argus.observedetection.watcher;

import dev.datrollout.argus.observedetection.model.ProbeTarget;
import dev.datrollout.argus.observedetection.probe.ProbeHeuristics;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class ProbeTargetExtractor {

    private static final int DEFAULT_HTTP_PORT = 80;
    private static final int DEFAULT_HTTPS_PORT = 443;

    public Optional<ProbeTarget> fromService(Service service) {
        String namespace = service.getMetadata().getNamespace();
        String name = service.getMetadata().getName();
        Map<String, String> labels = nullSafe(service.getMetadata().getLabels());
        Map<String, String> annotations = nullSafe(service.getMetadata().getAnnotations());

        List<Integer> ports = new ArrayList<>();
        if (service.getSpec() != null && service.getSpec().getPorts() != null) {
            for (ServicePort sp : service.getSpec().getPorts()) {
                if (sp.getPort() != null) ports.add(sp.getPort());
            }
        }

        int primaryPort = ProbeHeuristics.selectProbePort(ports, name);
        String scheme = primaryPort == DEFAULT_HTTPS_PORT ? "https" : "http";
        String dns = name + "." + namespace + ".svc.cluster.local";
        URI baseUrl = URI.create(scheme + "://" + dns + ":" + primaryPort);

        return Optional.of(ProbeTarget.builder()
                .namespace(namespace)
                .serviceName(name)
                .baseUrl(baseUrl)
                .labels(labels)
                .annotations(annotations)
                .ports(ports)
                .containerImages(List.of())
                .sourceKind(ProbeTarget.SourceKind.SERVICE)
                .build());
    }

    public Optional<ProbeTarget> fromPod(Pod pod) {
        String namespace = pod.getMetadata().getNamespace();
        String name = pod.getMetadata().getName();
        Map<String, String> labels = nullSafe(pod.getMetadata().getLabels());

        String podIp = pod.getStatus() != null ? pod.getStatus().getPodIP() : null;
        if (podIp == null || podIp.isBlank()) return Optional.empty();

        List<String> images = new ArrayList<>();
        List<Integer> ports = new ArrayList<>();

        if (pod.getSpec() != null && pod.getSpec().getContainers() != null) {
            for (Container c : pod.getSpec().getContainers()) {
                if (c.getImage() != null) images.add(c.getImage());
                if (c.getPorts() != null) {
                    c.getPorts().forEach(p -> {
                        if (p.getContainerPort() != null) ports.add(p.getContainerPort());
                    });
                }
            }
        }

        int primaryPort = ProbeHeuristics.selectProbePort(ports, name);
        URI baseUrl = URI.create("http://" + podIp + ":" + primaryPort);

        return Optional.of(ProbeTarget.builder()
                .namespace(namespace)
                .serviceName(name)
                .baseUrl(baseUrl)
                .labels(labels)
                .annotations(Map.of())
                .ports(ports)
                .containerImages(images)
                .sourceKind(ProbeTarget.SourceKind.POD)
                .build());
    }

    private Map<String, String> nullSafe(Map<String, String> map) {
        return map != null ? map : Map.of();
    }
}

package dev.datrollout.argus.observedetection.probe;

import dev.datrollout.argus.observedetection.model.ProbeTarget;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ProbeHeuristics {

    private static final List<Integer> LOKI_PORTS = List.of(3100, 3101);
    private static final List<Integer> PROMETHEUS_PORTS = List.of(9090, 9091);

    private ProbeHeuristics() {}

    public static boolean isObservabilityTarget(ProbeTarget target) {
        return looksLikeLoki(target) || looksLikePrometheus(target);
    }

    public static boolean looksLikeLoki(ProbeTarget target) {
        if (isLokiServerService(target.getServiceName())) return true;
        if (hasKeyword(target.getLabels(), "loki", "grafana/loki")) return true;
        if (hasImageKeyword(target.getContainerImages(), "loki")) return true;
        return exposesPort(target, LOKI_PORTS) && isLokiServerService(target.getServiceName());
    }

    public static boolean looksLikePrometheus(ProbeTarget target) {
        if (isPrometheusServerService(target.getServiceName())) return true;
        if (hasKeyword(target.getLabels(), "prometheus") && isPrometheusServerService(target.getServiceName())) {
            return true;
        }
        if (hasImageKeyword(target.getContainerImages(), "prometheus", "mimir", "thanos", "victoriametrics")) {
            return true;
        }
        return exposesPort(target, PROMETHEUS_PORTS) && isPrometheusServerService(target.getServiceName());
    }

    /**
     * Key for deduplicating alias Services (e.g. loki vs loki-headless), not unrelated workloads
     * in the same namespace.
     */
    public static Optional<String> providerKey(ProbeTarget target) {
        String serviceName = target.getServiceName();
        if (serviceName == null || serviceName.isBlank()) return Optional.empty();

        String normalized = normalizeServiceName(serviceName);
        if (isLokiServerName(normalized)) {
            return Optional.of("LOKI|" + target.getNamespace());
        }
        if (isPrometheusServerName(normalized)) {
            return Optional.of("PROMETHEUS|" + target.getNamespace());
        }
        return Optional.empty();
    }

    public static boolean isLokiServerService(String serviceName) {
        return isLokiServerName(normalizeServiceName(serviceName));
    }

    public static int selectProbePort(List<Integer> ports, String serviceName) {
        if (ports == null || ports.isEmpty()) return 80;
        if (isPrometheusServerService(serviceName)) {
            for (int port : PROMETHEUS_PORTS) {
                if (ports.contains(port)) return port;
            }
        }
        if (isLokiServerService(serviceName)) {
            for (int port : LOKI_PORTS) {
                if (ports.contains(port)) return port;
            }
        }
        for (int preferred : List.of(9090, 3100, 8080, 80, 443)) {
            if (ports.contains(preferred)) return preferred;
        }
        return ports.getFirst();
    }

    private static boolean isLokiServerName(String normalizedName) {
        return "loki".equals(normalizedName);
    }

    public static boolean isPrometheusServerService(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) return false;
        String lower = serviceName.toLowerCase();
        if (isExcludedPrometheusCompanion(lower)) return false;
        return isPrometheusServerName(normalizeServiceName(serviceName));
    }

    private static boolean isPrometheusServerName(String normalizedName) {
        return "prometheus".equals(normalizedName) || normalizedName.endsWith("-prometheus");
    }

    private static boolean isExcludedPrometheusCompanion(String serviceNameLower) {
        return serviceNameLower.contains("alertmanager")
                || serviceNameLower.contains("grafana")
                || serviceNameLower.contains("operator")
                || serviceNameLower.contains("kube-state-metrics")
                || serviceNameLower.contains("node-exporter")
                || serviceNameLower.contains("thanos");
    }

    private static String normalizeServiceName(String serviceName) {
        String lower = serviceName.toLowerCase();
        if (lower.endsWith("-headless")) {
            return lower.substring(0, lower.length() - "-headless".length());
        }
        return lower;
    }

    private static boolean exposesPort(ProbeTarget target, List<Integer> ports) {
        if (target.getPorts() == null) return false;
        return target.getPorts().stream().anyMatch(ports::contains);
    }

    private static boolean hasKeyword(Map<String, String> labels, String... keywords) {
        if (labels == null || labels.isEmpty()) return false;
        for (String keyword : keywords) {
            if (labels.containsKey(keyword) || labels.containsValue(keyword)) return true;
            for (String value : labels.values()) {
                if (value != null && value.toLowerCase().contains(keyword)) return true;
            }
        }
        return false;
    }

    private static boolean hasImageKeyword(List<String> images, String... keywords) {
        if (images == null || images.isEmpty()) return false;
        for (String image : images) {
            String lower = image.toLowerCase();
            for (String keyword : keywords) {
                if (lower.contains(keyword)) return true;
            }
        }
        return false;
    }

}

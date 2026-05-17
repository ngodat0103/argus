package dev.datrollout.argus.observedetection.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "discovery")
public record DiscoveryProperties(
        @DefaultValue("") List<String> namespaces,
        ProbeProperties probe,
        SecurityProperties security,
        CacheProperties cache,
        PortForwardProperties portForward
) {

    public record ProbeProperties(
            @DefaultValue("3s") Duration timeout,
            @DefaultValue("2") int retryCount
    ) {}

    public record SecurityProperties(
            @DefaultValue("10.0.0.0/8,172.16.0.0/12,192.168.0.0/16") List<String> allowedCidrs
    ) {}

    public record CacheProperties(
            @DefaultValue("5m") Duration ttl
    ) {}

    /**
     * Controls on-demand port-forward tunnels for local development.
     * Tunnels are created through the Fabric8 client (Kubernetes API server),
     * so no {@code kubectl} binary is required.
     */
    public record PortForwardProperties(
            /** Set to {@code true} when running outside the cluster (local dev). */
            @DefaultValue("false") boolean enabled
    ) {}
}

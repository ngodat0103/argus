package dev.datrollout.argus.observedetection.model;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;

@Getter
public class DetectionResult {

    private final String providerType;
    private final Set<Capability> capabilities;
    private final double confidenceScore;
    private final List<String> evidence;
    private final URI endpoint;
    private final ConfidenceLevel confidenceLevel;

    private DetectionResult(Builder builder) {
        this.providerType = builder.providerType;
        this.capabilities = builder.capabilities.isEmpty()
                ? EnumSet.noneOf(Capability.class)
                : EnumSet.copyOf(builder.capabilities);
        this.confidenceScore = builder.confidenceScore;
        this.evidence = List.copyOf(builder.evidence);
        this.endpoint = builder.endpoint;
        this.confidenceLevel = ConfidenceLevel.fromScore(builder.confidenceScore);
    }

    public boolean hasCapability(Capability capability) {
        return capabilities.contains(capability);
    }

    /** Returns a copy with the stable cluster endpoint used for registry and client lookup. */
    public DetectionResult withEndpoint(URI newEndpoint) {
        return builder()
                .providerType(providerType)
                .capabilities(capabilities)
                .confidenceScore(confidenceScore)
                .endpoint(newEndpoint)
                .evidence(evidence)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String providerType;
        private Set<Capability> capabilities = EnumSet.noneOf(Capability.class);
        private double confidenceScore;
        private final List<String> evidence = new ArrayList<>();
        private URI endpoint;

        public Builder providerType(String providerType) {
            this.providerType = providerType;
            return this;
        }

        public Builder addCapability(Capability capability) {
            this.capabilities.add(capability);
            return this;
        }

        public Builder capabilities(Set<Capability> caps) {
            this.capabilities = caps.isEmpty() ? EnumSet.noneOf(Capability.class) : EnumSet.copyOf(caps);
            return this;
        }

        public Builder confidenceScore(double score) {
            this.confidenceScore = score;
            return this;
        }

        public Builder addEvidence(String e) {
            this.evidence.add(e);
            return this;
        }

        public Builder evidence(List<String> evidence) {
            this.evidence.clear();
            this.evidence.addAll(evidence);
            return this;
        }

        public Builder endpoint(URI endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public DetectionResult build() {
            return new DetectionResult(this);
        }
    }
}

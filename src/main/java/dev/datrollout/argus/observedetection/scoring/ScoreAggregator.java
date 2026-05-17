package dev.datrollout.argus.observedetection.scoring;

import dev.datrollout.argus.observedetection.model.Capability;
import dev.datrollout.argus.observedetection.model.ConfidenceLevel;
import dev.datrollout.argus.observedetection.model.DetectionResult;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public class ScoreAggregator {

    private final String providerType;
    private final URI endpoint;
    private double totalScore = 0;
    private final Set<Capability> capabilities = EnumSet.noneOf(Capability.class);
    private final List<String> evidence = new ArrayList<>();

    public ScoreAggregator(String providerType, URI endpoint) {
        this.providerType = providerType;
        this.endpoint = endpoint;
    }

    public ScoreAggregator addSignal(double score, String description) {
        totalScore += score;
        evidence.add(String.format("[+%.0f] %s", score, description));
        return this;
    }

    public ScoreAggregator addCapability(Capability capability) {
        capabilities.add(capability);
        return this;
    }

    public double getScore() { return totalScore; }

    public ConfidenceLevel getConfidenceLevel() {
        return ConfidenceLevel.fromScore(totalScore);
    }

    public DetectionResult build() {
        DetectionResult.Builder builder = DetectionResult.builder()
                .providerType(providerType)
                .endpoint(endpoint)
                .confidenceScore(totalScore)
                .capabilities(capabilities);
        evidence.forEach(builder::addEvidence);
        return builder.build();
    }
}

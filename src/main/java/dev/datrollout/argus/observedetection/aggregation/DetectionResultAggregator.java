package dev.datrollout.argus.observedetection.aggregation;

import dev.datrollout.argus.observedetection.model.Capability;
import dev.datrollout.argus.observedetection.model.DetectionResult;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class DetectionResultAggregator {

    /**
     * Groups results by provider, then merges each group. Cross-provider results are never combined.
     */
    public List<DetectionResult> aggregateByProvider(List<DetectionResult> results) {
        return results.stream()
                .collect(java.util.stream.Collectors.groupingBy(DetectionResult::getProviderType))
                .values()
                .stream()
                .map(this::aggregate)
                .toList();
    }

    /**
     * Merges multiple DetectionResults for the same endpoint and provider.
     * Scores are summed, capabilities are unioned, evidence is concatenated.
     */
    public DetectionResult aggregate(List<DetectionResult> results) {
        if (results.isEmpty()) throw new IllegalArgumentException("Cannot aggregate empty results");
        if (results.size() == 1) return results.getFirst();

        URI endpoint = results.getFirst().getEndpoint();
        String providerType = results.getFirst().getProviderType();
        double totalScore = results.stream()
                .mapToDouble(DetectionResult::getConfidenceScore)
                .sum();

        Set<Capability> allCapabilities = EnumSet.noneOf(Capability.class);
        List<String> allEvidence = new ArrayList<>();

        for (DetectionResult r : results) {
            allCapabilities.addAll(r.getCapabilities());
            allEvidence.addAll(r.getEvidence());
        }

        DetectionResult.Builder builder = DetectionResult.builder()
                .providerType(providerType)
                .endpoint(endpoint)
                .confidenceScore(totalScore)
                .capabilities(allCapabilities);
        allEvidence.forEach(builder::addEvidence);
        return builder.build();
    }
}

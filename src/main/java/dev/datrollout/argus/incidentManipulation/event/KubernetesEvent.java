package dev.datrollout.argus.incidentManipulation.event;

import com.embabel.agent.api.reference.LlmReference;
import com.embabel.chat.Asset;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.Objects;
import lombok.Getter;
import org.jetbrains.annotations.NotNull;

@Getter
public abstract class KubernetesEvent implements LlmReference, Asset {
    protected Pod associatedPod;
    protected Event associatedEvent;

    public KubernetesEvent(Pod associatedPod) {
        this.associatedPod = associatedPod;
    }

    public KubernetesEvent(Event associatedEvent) {
        this.associatedEvent = associatedEvent;
    }

    public final String getWorkKey() {
        if (associatedPod != null) {
            return "pod/%s/%s"
                    .formatted(
                            associatedPod.getMetadata().getNamespace(),
                            associatedPod.getMetadata().getName());
        }
        if (associatedEvent != null) {
            return "event/%s/%s"
                    .formatted(
                            associatedEvent.getMetadata().getNamespace(),
                            associatedEvent.getMetadata().getName());
        }
        throw new IllegalStateException("AbstractKubernetesEvent has no associated resource");
    }

    @Override
    public @NotNull LlmReference reference() {
        return this;
    }

    @Override
    public @NotNull String getId() {
        return this.getWorkKey();
    }

    @Override
    public boolean persistent() {
        return true;
    }

    @Override
    public @NotNull Instant getTimestamp() {
        if (associatedPod != null) {
            // 1. Try terminated container state (most precise for OOMKill, CrashLoopBackOff, etc.)
            if (associatedPod.getStatus() != null && associatedPod.getStatus().getContainerStatuses() != null) {
                var terminatedAt = associatedPod.getStatus().getContainerStatuses().stream()
                        .filter(cs -> cs.getLastState() != null
                                && cs.getLastState().getTerminated() != null
                                && cs.getLastState().getTerminated().getFinishedAt() != null)
                        .map(cs -> cs.getLastState().getTerminated().getFinishedAt())
                        .map(KubernetesEvent::parseK8sTimestamp)
                        .filter(Objects::nonNull)
                        .max(Comparator.naturalOrder()); // most recent termination wins

                if (terminatedAt.isPresent()) {
                    return terminatedAt.get();
                }
            }

            // 2. Fallback to pod creation timestamp
            if (associatedPod.getMetadata() != null
                    && associatedPod.getMetadata().getCreationTimestamp() != null) {
                var parsed = parseK8sTimestamp(associatedPod.getMetadata().getCreationTimestamp());
                if (parsed != null) return parsed;
            }
        }

        if (associatedEvent != null) {
            // 3. Event timestamps in precision order: eventTime > lastTimestamp > firstTimestamp
            if (associatedEvent.getEventTime() != null
                    && associatedEvent.getEventTime().getTime() != null) {
                var parsed = parseK8sTimestamp(associatedEvent.getEventTime().getTime());
                if (parsed != null) return parsed;
            }
            if (associatedEvent.getLastTimestamp() != null) {
                var parsed = parseK8sTimestamp(associatedEvent.getLastTimestamp());
                if (parsed != null) return parsed;
            }
            if (associatedEvent.getFirstTimestamp() != null) {
                var parsed = parseK8sTimestamp(associatedEvent.getFirstTimestamp());
                if (parsed != null) return parsed;
            }
        }

        throw new IllegalStateException("KubernetesEvent has no extractable timestamp");
    }

    /**
     * Parses a Kubernetes RFC 3339 / ISO-8601 timestamp string (e.g. "2024-11-01T12:34:56Z").
     * Returns null on blank/unparseable input so callers can cleanly chain fallbacks.
     */
    private static Instant parseK8sTimestamp(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}

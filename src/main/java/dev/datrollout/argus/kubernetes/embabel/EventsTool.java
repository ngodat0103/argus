package dev.datrollout.argus.kubernetes.embabel;

import com.embabel.agent.api.annotation.LlmTool;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.MicroTime;
import io.fabric8.kubernetes.api.model.ObjectReference;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * LLM-callable tools for inspecting cluster Events.
 *
 * <p>Senior on-call engineers always start with `kubectl get events --sort-by=...`. These
 * tools surface the same view as a first-class signal — namespace, cluster-wide, or per-object,
 * with Warning-only filtering and a configurable time window.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class EventsTool {

    private static final int MAX_EVENTS = 50;
    private static final String FILTER_WARNING = "warning";
    private static final String FILTER_NORMAL = "normal";
    private static final String FILTER_ALL = "all";

    private final KubernetesClient kubernetesClient;

    // ──────────────────────────────────────────────────────────────────────────
    // LLM tools
    // ──────────────────────────────────────────────────────────────────────────

    @LlmTool(
            name = "listRecentEvents",
            description = """
                    Use this tool as the FIRST step when investigating any namespaced incident
                    ("what just went wrong in <namespace>"). Returns the most recent Events in a
                    namespace, sorted newest-first.
                    typeFilter: 'Warning' (most actionable), 'Normal', or 'All'.
                    sinceMinutes: only events seen within this window (0 = no time filter).
                    Output is capped at 50 events. Each line shows: timestamp, type, reason,
                    involved object (kind/name), source component, and message.
                    Includes a counts-by-reason summary so the LLM can spot patterns
                    (e.g. 5x BackOff, 3x FailedScheduling).
                    """
    )
    public String listRecentEvents(String namespace, String typeFilter, int sinceMinutes) {
        if (namespace == null || namespace.isBlank()) {
            return "ERROR: namespace must not be empty. Use listClusterEvents for cluster-wide.";
        }
        String filter = normaliseFilter(typeFilter);
        Instant cutoff = computeCutoff(sinceMinutes);

        List<Event> events;
        try {
            events = kubernetesClient.v1().events().inNamespace(namespace).list().getItems();
        } catch (KubernetesClientException e) {
            return "ERROR: could not list events in " + namespace + ": " + e.getMessage();
        }
        return renderEvents(events, "RECENT EVENTS: " + namespace, filter, cutoff, namespace);
    }

    @LlmTool(
            name = "listClusterEvents",
            description = """
                    Use this tool when the operator is investigating a cluster-wide problem and
                    has not narrowed it to a single namespace ("the cluster is sad", "what's
                    breaking everywhere"). Returns the most recent Events across ALL namespaces.
                    sinceMinutes: time window in minutes (0 = no time filter).
                    Always Warning-only — the cluster generates far too many Normal events to be
                    useful at this scope. Capped at 50 events.
                    """
    )
    public String listClusterEvents(int sinceMinutes) {
        Instant cutoff = computeCutoff(sinceMinutes);
        List<Event> events;
        try {
            events = kubernetesClient.v1().events().inAnyNamespace().list().getItems();
        } catch (KubernetesClientException e) {
            return "ERROR: could not list cluster events: " + e.getMessage();
        }
        return renderEvents(events, "CLUSTER EVENTS (Warning only)", FILTER_WARNING, cutoff, null);
    }

    @LlmTool(
            name = "findEventsForObject",
            description = """
                    Use this tool to fetch every Event tied to one specific Kubernetes object
                    (e.g. why is THIS pod stuck, why is THIS PVC pending). Pass the namespace
                    and the object's kind (Pod, Deployment, ReplicaSet, StatefulSet, DaemonSet,
                    Service, Ingress, PersistentVolumeClaim, Job, CronJob, Node, etc.) and exact
                    name. Returns events sorted newest-first regardless of type.
                    """
    )
    public String findEventsForObject(String namespace, String kind, String name) {
        if (kind == null || kind.isBlank() || name == null || name.isBlank()) {
            return "ERROR: kind and name are required.";
        }
        List<Event> all;
        try {
            boolean clusterScoped = "node".equalsIgnoreCase(kind);
            all = clusterScoped
                    ? kubernetesClient.v1().events().inAnyNamespace().list().getItems()
                    : (namespace == null || namespace.isBlank()
                            ? kubernetesClient.v1().events().inAnyNamespace().list().getItems()
                            : kubernetesClient.v1().events().inNamespace(namespace).list().getItems());
        } catch (KubernetesClientException e) {
            return "ERROR: could not list events: " + e.getMessage();
        }

        List<Event> filtered = all.stream()
                .filter(e -> matchesObject(e, kind, name))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("=== EVENTS FOR ").append(kind).append(" ");
        if (namespace != null && !namespace.isBlank()) sb.append(namespace).append("/");
        sb.append(name).append(" — ").append(filtered.size()).append(" event(s) ===\n\n");

        if (filtered.isEmpty()) {
            sb.append("(none)\n");
            sb.append("Hints:\n");
            sb.append("  - Check that '").append(kind).append("' and the name are exact (case-sensitive).\n");
            sb.append("  - Some controllers emit events to a different namespace (Endpoint events live\n");
            sb.append("    where the Service does, not the pod).\n");
            return sb.toString();
        }
        appendEventTable(sb, sortByRecency(filtered).stream().limit(MAX_EVENTS).toList());
        appendCountsByReason(sb, filtered);
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internals
    // ──────────────────────────────────────────────────────────────────────────

    private String renderEvents(List<Event> events, String header, String filter,
                                Instant cutoff, String singleNamespace) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(header)
                .append("  filter=").append(filter);
        if (cutoff != null) sb.append("  since=").append(cutoff);
        sb.append(" ===\n\n");

        List<Event> filtered = events.stream()
                .filter(e -> matchesType(e, filter))
                .filter(e -> matchesTime(e, cutoff))
                .collect(Collectors.toList());

        if (filtered.isEmpty()) {
            sb.append("(no events match the filter)\n");
            if (FILTER_WARNING.equals(filter)) {
                sb.append("Try typeFilter='All' to see Normal events too,\n");
                sb.append("or widen sinceMinutes (currently ")
                        .append(cutoff == null ? "unbounded" : cutoff).append(").\n");
            }
            return sb.toString();
        }

        List<Event> sorted = sortByRecency(filtered);
        appendEventTable(sb, sorted.stream().limit(MAX_EVENTS).toList());
        if (sorted.size() > MAX_EVENTS) {
            sb.append("\n... (").append(sorted.size() - MAX_EVENTS)
                    .append(" more events not shown — narrow by typeFilter or sinceMinutes)\n");
        }
        appendCountsByReason(sb, filtered);
        if (singleNamespace == null) {
            appendCountsByNamespace(sb, filtered);
        }
        return sb.toString();
    }

    private void appendEventTable(StringBuilder sb, List<Event> events) {
        sb.append(String.format("%-25s  %-7s  %-22s  %-45s  %s%n",
                "WHEN", "TYPE", "REASON", "OBJECT", "MESSAGE"));
        sb.append("-".repeat(150)).append('\n');
        for (Event e : events) {
            String when = bestTimestamp(e);
            String type = safe(e.getType());
            String reason = safe(e.getReason());
            String obj = formatObject(e.getInvolvedObject(),
                    Optional.ofNullable(e.getMetadata()).map(m -> m.getNamespace()).orElse(null));
            String msg = safe(e.getMessage()).replaceAll("\\s+", " ");
            sb.append(String.format("%-25s  %-7s  %-22s  %-45s  %s%n",
                    truncate(when, 25), truncate(type, 7), truncate(reason, 22),
                    truncate(obj, 45), truncate(msg, 200)));
        }
    }

    private void appendCountsByReason(StringBuilder sb, List<Event> events) {
        Map<String, Long> reasonCounts = events.stream()
                .collect(Collectors.groupingBy(e -> safe(e.getReason()), Collectors.counting()));
        if (reasonCounts.isEmpty()) return;
        sb.append("\n[Counts by reason]\n");
        reasonCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(20)
                .forEach(en -> sb.append(String.format("  %-30s  %d%n", en.getKey(), en.getValue())));
    }

    private void appendCountsByNamespace(StringBuilder sb, List<Event> events) {
        Map<String, Long> nsCounts = events.stream()
                .collect(Collectors.groupingBy(
                        e -> Optional.ofNullable(e.getMetadata()).map(m -> m.getNamespace()).orElse("?"),
                        Collectors.counting()));
        if (nsCounts.size() <= 1) return;
        sb.append("\n[Counts by namespace]\n");
        nsCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(20)
                .forEach(en -> sb.append(String.format("  %-30s  %d%n", en.getKey(), en.getValue())));
    }

    private boolean matchesType(Event e, String filter) {
        if (FILTER_ALL.equals(filter)) return true;
        String t = e.getType();
        if (t == null) return false;
        if (FILTER_WARNING.equals(filter)) return "Warning".equalsIgnoreCase(t);
        if (FILTER_NORMAL.equals(filter)) return "Normal".equalsIgnoreCase(t);
        return true;
    }

    private boolean matchesTime(Event e, Instant cutoff) {
        if (cutoff == null) return true;
        Instant t = effectiveInstant(e);
        return t == null || !t.isBefore(cutoff);
    }

    private boolean matchesObject(Event e, String kind, String name) {
        ObjectReference ref = e.getInvolvedObject();
        if (ref == null) return false;
        return kind.equalsIgnoreCase(ref.getKind()) && name.equals(ref.getName());
    }

    private List<Event> sortByRecency(List<Event> events) {
        return events.stream()
                .sorted(Comparator.comparing(this::effectiveInstant,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private Instant effectiveInstant(Event e) {
        // Try lastTimestamp, eventTime, firstTimestamp in that order.
        Stream<String> candidates = Stream.of(
                e.getLastTimestamp(),
                e.getFirstTimestamp(),
                Optional.ofNullable(e.getEventTime()).map(MicroTime::getTime).orElse(null));
        return candidates.filter(s -> s != null && !s.isBlank())
                .map(this::parseInstant)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
    }

    private Instant parseInstant(String iso) {
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private Instant computeCutoff(int sinceMinutes) {
        if (sinceMinutes <= 0) return null;
        return Instant.now().minusSeconds(60L * sinceMinutes);
    }

    private String bestTimestamp(Event e) {
        Instant t = effectiveInstant(e);
        if (t != null) return t.toString();
        return Optional.ofNullable(e.getLastTimestamp())
                .or(() -> Optional.ofNullable(e.getFirstTimestamp()))
                .orElse("?");
    }

    private String formatObject(ObjectReference ref, String eventNs) {
        if (ref == null) return "?";
        String kind = safe(ref.getKind());
        String name = safe(ref.getName());
        String ns = ref.getNamespace();
        if (ns == null || ns.isBlank()) ns = eventNs;
        if (ns == null || ns.isBlank()) return kind + "/" + name;
        return kind + " " + ns + "/" + name;
    }

    private String normaliseFilter(String typeFilter) {
        if (typeFilter == null || typeFilter.isBlank()) return FILTER_WARNING;
        String f = typeFilter.toLowerCase(Locale.ROOT);
        return switch (f) {
            case "warning", "warn", "w" -> FILTER_WARNING;
            case "normal", "n" -> FILTER_NORMAL;
            case "all", "*", "any" -> FILTER_ALL;
            default -> FILTER_WARNING;
        };
    }

    private String safe(String s) {
        return s == null || s.isBlank() ? "?" : s;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(1, max - 1)) + "…";
    }
}

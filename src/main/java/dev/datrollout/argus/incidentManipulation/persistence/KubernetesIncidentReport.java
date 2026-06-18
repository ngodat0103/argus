package dev.datrollout.argus.incidentManipulation.persistence;

import com.embabel.common.ai.prompt.PromptContributor;
import jakarta.persistence.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Base SRE incident report. Holds identity, lifecycle, impact, and timeline
 * fields common across all incident types. Subclasses carry signal-specific
 * diagnostic fields.
 *
 * Designed to be serializable as a GOAP world-state domain object and
 * persistable as a JPA entity (use @MappedSuperclass + @Entity on leaves).
 */
@MappedSuperclass
@Setter
@Getter
@NoArgsConstructor
public abstract class KubernetesIncidentReport implements PromptContributor {

    // ─── Identity ────────────────────────────────────────────────────────────

    @Id
    private String incidentId; // e.g. "INC-20250616-oomkill-abc12"

    private String name; // Human-readable title
    private String executionSummary; // LLM-generated or operator-authored summary

    @Enumerated(EnumType.STRING)
    private IncidentSeverity severity; // P1..P4

    @Enumerated(EnumType.STRING)
    private IncidentStatus status; // DETECTING | DIAGNOSING | REMEDIATING | RESOLVED | SUPPRESSED

    // ─── Kubernetes Topology ─────────────────────────────────────────────────

    private String clusterName;
    private String namespace;
    private String workloadKind; // Deployment | StatefulSet | DaemonSet | Job
    private String workloadName;
    private String nodeName; // May be null if node-agnostic

    // ─── Lifecycle / Timing ──────────────────────────────────────────────────

    private Instant detectedAt; // When the agent first saw the signal
    private Instant confirmedAt; // When the agent verified (post-scan)
    private Instant resolvedAt; // Null until resolution
    private Instant reportGeneratedAt;

    // ─── Source Signals ──────────────────────────────────────────────────────

    /**
     * Which detection path fired first: WATCHER (real-time Pod MODIFIED),
     * SCAN (periodic action), or EVENT (K8s Event API).
     */
    @Enumerated(EnumType.STRING)
    private DetectionSource detectionSource;

    /** Raw K8s Event UIDs that corroborate this incident. */
    @ElementCollection
    private List<String> correlatedEventUids = new ArrayList<>();

    // ─── Impact ──────────────────────────────────────────────────────────────

    private int affectedPodCount;
    private int affectedContainerCount;
    private boolean serviceImpacted; // Any endpoint health check degraded
    private String impactSummary; // LLM-authored one-liner for the report header

    // ─── Agent Reasoning ─────────────────────────────────────────────────────

    /** The active hypothesis at the time this report snapshot was taken. */
    private String hypothesisLabel;

    /** Confidence score [0.0–1.0] from the triage LLM call. */
    private double hypothesisConfidence;

    /** Actions the agent took or proposes. Ordered chronologically. */
    @ElementCollection
    @OrderColumn
    private List<String> agentActionLog = new ArrayList<>();

    // ─── Remediation ─────────────────────────────────────────────────────────

    /**
     * Structured hints emitted by the diagnostic actions for use by the
     * planner when constructing a RemediationPlan.
     * Key = hint type (e.g. "INCREASE_MEMORY_LIMIT"), value = suggested value.
     */
    @ElementCollection
    private Map<String, String> remediationHints;

    private boolean requiresHumanApproval; // Gating flag for destructive actions
    private String approvedBy; // Operator ID if gate was passed

    // ─── Timeline Events ─────────────────────────────────────────────────────

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "incident_id")
    @OrderColumn(name = "event_order")
    private List<IncidentTimelineEvent> timeline = new ArrayList<>();

    @Override
    public String toString() {
        return "%s{incidentId=%s, type=%s, name=%s, severity=%s, status=%s, namespace=%s, workload=%s/%s, detectedAt=%s}"
                .formatted(
                        getClass().getSimpleName(),
                        incidentId,
                        incidentTypeTag(),
                        name,
                        severity,
                        status,
                        namespace,
                        workloadKind,
                        workloadName,
                        detectedAt);
    }

    // ─── Supporting types ────────────────────────────────────────────────────

    public enum IncidentSeverity {
        P1,
        P2,
        P3,
        P4
    }

    public enum IncidentStatus {
        DETECTED,
        DIAGNOSING,
        REMEDIATING,
        RESOLVED,
        SUPPRESSED
    }

    public enum DetectionSource {
        WATCHER,
        SCAN,
        EVENT,
        MANUAL
    }

    @PrePersist
    private void generateId() {
        if (this.incidentId == null) {
            String date = DateTimeFormatter.ofPattern("yyyyMMdd")
                    .withZone(ZoneOffset.UTC)
                    .format(Instant.now());
            String type = incidentTypeTag();
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            this.incidentId = "INC-%s-%s-%s".formatted(date, type, suffix);
            // e.g. INC-20250616-oomkill-a3f9c12b
        }
    }

    protected abstract String incidentTypeTag();

    // ─── Getters/setters (Lombok @Data/@Getter/@Setter works too) ────────────
    // omitted for brevity; use Lombok in production
}

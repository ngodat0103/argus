package dev.datrollout.argus.kubernetes.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only log entry for the incident timeline. */
@Entity
@Table(name = "incident_timeline_event")
public class IncidentTimelineEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    private EventKind kind; // SIGNAL | ACTION | HYPOTHESIS_CHANGE | HUMAN | RESOLUTION

    private String actor; // "agent", "watcher", or operator name
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> additionalData;

    public enum EventKind {
        SIGNAL,
        ACTION,
        HYPOTHESIS_CHANGE,
        HUMAN_INPUT,
        RESOLUTION
    }
}

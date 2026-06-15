package dev.datrollout.argus.kubernetes.phase;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.Pod;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.annotation.PersistenceCreator;

@Getter
@SuperBuilder
@Slf4j
@NoArgsConstructor
@AllArgsConstructor(onConstructor_ = @PersistenceCreator)
@JsonIgnoreProperties(ignoreUnknown = true)
@Setter
public abstract class K8sEventWrapper {
    protected Pod failedPod;
    protected Event associatedEvent;
    // Time window configuration
    protected static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    protected static final DateTimeFormatter HOUR_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH");
    protected static final DateTimeFormatter WEEK_FORMATTER = DateTimeFormatter.ofPattern("yyyy-'W'ww");
    protected static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    private String fingerprint;

    public String getEventType() {
        return this.getClass().getSimpleName();
    }

    /** Extract firstTimestamp from event */
    protected Instant extractFirstTimestamp() {
        try {
            if (this.associatedEvent == null) {
                return null;
            }
            // Try firstTimestamp
            String firstTimestamp = this.associatedEvent.getFirstTimestamp();
            if (firstTimestamp != null && !firstTimestamp.isEmpty()) {
                return Instant.parse(firstTimestamp);
            }
            // Fallback to lastTimestamp
            String lastTimestamp = this.associatedEvent.getLastTimestamp();
            if (lastTimestamp != null && !lastTimestamp.isEmpty()) {
                return Instant.parse(lastTimestamp);
            }
            // Fallback to metadata.creationTimestamp
            if (this.associatedEvent.getMetadata() != null) {
                String creationTimestamp = this.associatedEvent.getMetadata().getCreationTimestamp();
                if (creationTimestamp != null && !creationTimestamp.isEmpty()) {
                    return Instant.parse(creationTimestamp);
                }
            }

        } catch (Exception e) {
            log.debug("Error extracting firstTimestamp for {}", getEventType(), e);
        }
        return null;
    }

    /** Extract namespace from event or pod */
    public String extractNamespace() {
        try {
            if (this.associatedEvent != null && this.associatedEvent.getInvolvedObject() != null) {
                String namespace = this.associatedEvent.getInvolvedObject().getNamespace();
                if (namespace != null && !namespace.isEmpty()) {
                    return namespace;
                }
            }

            if (this.failedPod != null && this.failedPod.getMetadata() != null) {
                String namespace = this.failedPod.getMetadata().getNamespace();
                if (namespace != null && !namespace.isEmpty()) {
                    return namespace;
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting namespace for {}", getEventType(), e);
        }
        return null;
    }

    /** Extract reason from event */
    protected String extractReason() {
        try {
            if (this.associatedEvent != null) {
                String reason = this.associatedEvent.getReason();
                if (reason != null && !reason.isEmpty()) {
                    return reason;
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting reason for {}", getEventType(), e);
        }
        return null;
    }

    /** Helper method to safely extract and trim string */
    protected String safeExtract(String value, String defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        return value.trim();
    }

    // Add these methods to your K8sEventWrapper class

    /**
     * Get the type of the event (Warning, Normal, etc.)
     *
     * @return event type string
     */
    public String getType() {
        try {
            if (this.associatedEvent != null) {
                String type = this.associatedEvent.getType();
                if (type != null && !type.isEmpty()) {
                    return type;
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting type for {}", getEventType(), e);
        }
        return "Unknown";
    }

    /**
     * Get the reason from the event
     *
     * @return event reason string
     */
    public String getReason() {
        return extractReason();
    }

    /**
     * Get the namespace from the event or pod
     *
     * @return namespace string
     */
    public String getNamespace() {
        return extractNamespace();
    }

    /**
     * Get labels from the involved object or pod
     *
     * @return map of labels
     */
    public java.util.Map<String, String> getLabels() {
        try {
            // Try to get from pod first
            if (this.failedPod != null
                    && this.failedPod.getMetadata() != null
                    && this.failedPod.getMetadata().getLabels() != null) {
                return this.failedPod.getMetadata().getLabels();
            }

            // Fallback to event's involved object (if it has labels)
            if (this.associatedEvent != null
                    && this.associatedEvent.getInvolvedObject() != null
                    && this.associatedEvent.getMetadata() != null
                    && this.associatedEvent.getMetadata().getLabels() != null) {
                return this.associatedEvent.getMetadata().getLabels();
            }
        } catch (Exception e) {
            log.debug("Error extracting labels for {}", getEventType(), e);
        }
        return java.util.Collections.emptyMap();
    }

    /**
     * Get annotations from the involved object or pod
     *
     * @return map of annotations
     */
    public java.util.Map<String, String> getAnnotations() {
        try {
            // Try to get from pod first
            if (this.failedPod != null
                    && this.failedPod.getMetadata() != null
                    && this.failedPod.getMetadata().getAnnotations() != null) {
                return this.failedPod.getMetadata().getAnnotations();
            }

            // Fallback to event's annotations
            if (this.associatedEvent != null
                    && this.associatedEvent.getMetadata() != null
                    && this.associatedEvent.getMetadata().getAnnotations() != null) {
                return this.associatedEvent.getMetadata().getAnnotations();
            }
        } catch (Exception e) {
            log.debug("Error extracting annotations for {}", getEventType(), e);
        }
        return java.util.Collections.emptyMap();
    }

    /**
     * Get the kind of the involved Kubernetes object
     *
     * @return object kind (Pod, Deployment, etc.)
     */
    public String getInvolvedObjectKind() {
        try {
            if (this.associatedEvent != null && this.associatedEvent.getInvolvedObject() != null) {
                String kind = this.associatedEvent.getInvolvedObject().getKind();
                if (kind != null && !kind.isEmpty()) {
                    return kind;
                }
            }

            // Fallback: if we have a pod, return "Pod"
            if (this.failedPod != null) {
                return "Pod";
            }
        } catch (Exception e) {
            log.debug("Error extracting involved object kind for {}", getEventType(), e);
        }
        return "Unknown";
    }

    /**
     * Get the name of the involved Kubernetes object
     *
     * @return object name
     */
    public String getInvolvedObjectName() {
        try {
            if (this.associatedEvent != null && this.associatedEvent.getInvolvedObject() != null) {
                String name = this.associatedEvent.getInvolvedObject().getName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }

            // Fallback: try to get from pod
            if (this.failedPod != null && this.failedPod.getMetadata() != null) {
                String name = this.failedPod.getMetadata().getName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting involved object name for {}", getEventType(), e);
        }
        return "unknown";
    }

    /**
     * Get the count of event occurrences
     *
     * @return event count
     */
    public Integer getCount() {
        try {
            if (this.associatedEvent != null) {
                Integer count = this.associatedEvent.getCount();
                if (count != null) {
                    return count;
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting count for {}", getEventType(), e);
        }
        return 1; // Default to 1 if not available
    }

    /**
     * Get the first timestamp of the event as Instant
     *
     * @return first timestamp as Instant
     */
    public Instant getFirstTimestamp() {
        return extractFirstTimestamp();
    }

    /**
     * Get the last timestamp of the event as Instant
     *
     * @return last timestamp as Instant
     */
    public Instant getLastTimestamp() {
        try {
            if (this.associatedEvent == null) {
                return null;
            }

            String lastTimestamp = this.associatedEvent.getLastTimestamp();
            if (lastTimestamp != null && !lastTimestamp.isEmpty()) {
                return Instant.parse(lastTimestamp);
            }

            // Fallback to firstTimestamp
            return extractFirstTimestamp();

        } catch (Exception e) {
            log.debug("Error extracting lastTimestamp for {}", getEventType(), e);
        }
        return null;
    }

    /**
     * Get the message from the event
     *
     * @return event message
     */
    public String getMessage() {
        try {
            if (this.associatedEvent != null) {
                String message = this.associatedEvent.getMessage();
                if (message != null && !message.isEmpty()) {
                    return message;
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting message for {}", getEventType(), e);
        }
        return "";
    }

    /**
     * Get the source component that generated the event
     *
     * @return source component name
     */
    public String getSourceComponent() {
        try {
            if (this.associatedEvent != null && this.associatedEvent.getSource() != null) {
                String component = this.associatedEvent.getSource().getComponent();
                if (component != null && !component.isEmpty()) {
                    return component;
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting source component for {}", getEventType(), e);
        }
        return "unknown";
    }

    /**
     * Get the reporting controller
     *
     * @return reporting controller name
     */
    public String getReportingController() {
        try {
            if (this.associatedEvent != null) {
                String controller = this.associatedEvent.getReportingComponent();
                if (controller != null && !controller.isEmpty()) {
                    return controller;
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting reporting controller for {}", getEventType(), e);
        }
        return "unknown";
    }

    /**
     * Get the container name if this is a container-level event
     *
     * @return container name or null
     */
    public String getContainerName() {
        try {
            if (this.associatedEvent != null
                    && this.associatedEvent.getInvolvedObject() != null
                    && this.associatedEvent.getInvolvedObject().getFieldPath() != null) {
                // Field path format: spec.containers{container-name}
                String fieldPath = this.associatedEvent.getInvolvedObject().getFieldPath();
                if (fieldPath.contains("spec.containers{")) {
                    int start = fieldPath.indexOf('{') + 1;
                    int end = fieldPath.indexOf('}');
                    if (start > 0 && end > start) {
                        return fieldPath.substring(start, end);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting container name for {}", getEventType(), e);
        }
        return null;
    }

    /**
     * Get the pod name
     *
     * @return pod name
     */
    public String getPodName() {
        try {
            if (this.failedPod != null && this.failedPod.getMetadata() != null) {
                String name = this.failedPod.getMetadata().getName();
                if (name != null && !name.isEmpty()) {
                    return name;
                }
            }

            // Fallback to involved object name if it's a Pod
            if ("Pod".equals(getInvolvedObjectKind())) {
                return getInvolvedObjectName();
            }
        } catch (Exception e) {
            log.debug("Error extracting pod name for {}", getEventType(), e);
        }
        return "unknown";
    }

    /**
     * Get the node name where the pod is running
     *
     * @return node name
     */
    public String getNodeName() {
        try {
            if (this.failedPod != null && this.failedPod.getSpec() != null) {
                String nodeName = this.failedPod.getSpec().getNodeName();
                if (nodeName != null && !nodeName.isEmpty()) {
                    return nodeName;
                }
            }
        } catch (Exception e) {
            log.debug("Error extracting node name for {}", getEventType(), e);
        }
        return "unknown";
    }
}

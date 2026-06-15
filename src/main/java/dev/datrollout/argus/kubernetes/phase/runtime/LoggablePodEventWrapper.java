package dev.datrollout.argus.kubernetes.phase.runtime;

import dev.datrollout.argus.kubernetes.phase.K8sEventWrapper;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;

@Getter
@SuperBuilder
@Slf4j
@AllArgsConstructor
public class LoggablePodEventWrapper extends K8sEventWrapper {
    private List<String> lineLogs;
    private static final Pattern CONTAINER_PATTERN = Pattern.compile("spec\\.(init)?[cC]ontainers\\{([^}]+)}");
    private static final Pattern FAILED_CONTAINER_PATTERN = Pattern.compile("failed container (.+)");
    private static final Pattern GENERIC_CONTAINER_PATTERN = Pattern.compile("container[:\\s]+([\\w-]+)");

    public String getFailedContainerName() {
        try {
            // Validate event object
            if (this.associatedEvent == null) {
                log.warn("Event object is null");
                return null;
            }

            if (this.associatedEvent.getInvolvedObject() == null) {
                log.warn(
                        "InvolvedObject is null for event: {}",
                        this.associatedEvent.getMetadata() != null
                                ? this.associatedEvent.getMetadata().getName()
                                : "unknown");
                return null;
            }

            String fieldPath = this.associatedEvent.getInvolvedObject().getFieldPath();

            // Check if fieldPath is empty
            if (fieldPath == null || fieldPath.trim().isEmpty()) {
                log.debug(
                        "FieldPath is null or empty for event: {}",
                        this.associatedEvent.getMetadata() != null
                                ? this.associatedEvent.getMetadata().getName()
                                : "unknown");
                return null;
            }

            log.debug("Attempting to extract container name from fieldPath: {}", fieldPath);
            String containerName = tryExtractWithPattern(fieldPath, CONTAINER_PATTERN, 2, "containers pattern");
            if (containerName != null) {
                return containerName;
            }
            containerName = tryExtractWithPattern(fieldPath, FAILED_CONTAINER_PATTERN, 1, "failed container pattern");
            if (containerName != null) {
                return containerName;
            }
            containerName = tryExtractWithPattern(fieldPath, GENERIC_CONTAINER_PATTERN, 1, "generic container pattern");
            if (containerName != null) {
                return containerName;
            }

            // No pattern matched
            log.warn(
                    "Could not extract container name from fieldPath: {}. Event: {}",
                    fieldPath,
                    this.associatedEvent.getMetadata() != null
                            ? this.associatedEvent.getMetadata().getName()
                            : "unknown");

            return null;

        } catch (Exception e) {
            log.error(
                    "Unexpected error while extracting container name from event: {}",
                    this.associatedEvent != null && this.associatedEvent.getMetadata() != null
                            ? this.associatedEvent.getMetadata().getName()
                            : "unknown",
                    e);
            return null;
        }
    }

    private String tryExtractWithPattern(String fieldPath, Pattern pattern, int groupIndex, String patternName) {
        try {
            Matcher matcher = pattern.matcher(fieldPath);
            if (matcher.find()) {
                String containerName = matcher.group(groupIndex);
                if (containerName != null && !containerName.trim().isEmpty()) {
                    containerName = containerName.trim();
                    log.debug("Successfully extracted container name '{}' using {}", containerName, patternName);
                    return containerName;
                }
            }
        } catch (Exception e) {
            log.debug("Error matching {} for fieldPath: {}", patternName, fieldPath, e);
        }
        return null;
    }

    public String getFailedContainerDetails() {
        String containerName = getFailedContainerName();
        if (containerName == null) {
            return "Unknown container";
        }
        StringBuilder details = new StringBuilder();
        details.append("Container: ").append(containerName);

        if (this.associatedEvent != null && this.associatedEvent.getInvolvedObject() != null) {
            String kind = this.associatedEvent.getInvolvedObject().getKind();
            String name = this.associatedEvent.getInvolvedObject().getName();
            String namespace = this.associatedEvent.getInvolvedObject().getNamespace();

            if (kind != null) {
                details.append(", Kind: ").append(kind);
            }
            if (name != null) {
                details.append(", Resource: ").append(name);
            }
            if (namespace != null) {
                details.append(", Namespace: ").append(namespace);
            }
        }

        return details.toString();
    }

    public boolean isInitContainerFailure() {
        if (this.associatedEvent == null || this.associatedEvent.getInvolvedObject() == null) {
            return false;
        }
        String fieldPath = this.associatedEvent.getInvolvedObject().getFieldPath();
        if (fieldPath == null) {
            return false;
        }

        return fieldPath.toLowerCase().contains("initcontainer");
    }
}

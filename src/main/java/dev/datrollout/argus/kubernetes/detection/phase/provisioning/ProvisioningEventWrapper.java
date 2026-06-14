package dev.datrollout.argus.kubernetes.detection.phase.provisioning;

import dev.datrollout.argus.kubernetes.detection.phase.K8sEventWrapper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.extern.slf4j.Slf4j;

@SuperBuilder
@Slf4j
@AllArgsConstructor
public abstract class ProvisioningEventWrapper extends K8sEventWrapper {
    private static final Pattern CONTAINER_PATTERN = Pattern.compile("spec\\.(init)?[cC]ontainers\\{([^}]+)}");
    private static final Pattern FAILED_CONTAINER_PATTERN = Pattern.compile("failed container (.+)");
    private static final Pattern GENERIC_CONTAINER_PATTERN = Pattern.compile("container[:\\s]+([\\w-]+)");

    public String getFailedContainerName() {
        try {
            // Validate event object
            if (this.event == null) {
                log.warn("Event object is null");
                return null;
            }

            if (this.event.getInvolvedObject() == null) {
                log.warn(
                        "InvolvedObject is null for event: {}",
                        this.event.getMetadata() != null
                                ? this.event.getMetadata().getName()
                                : "unknown");
                return null;
            }

            String fieldPath = this.event.getInvolvedObject().getFieldPath();

            // Check if fieldPath is empty
            if (fieldPath == null || fieldPath.trim().isEmpty()) {
                log.debug(
                        "FieldPath is null or empty for event: {}",
                        this.event.getMetadata() != null
                                ? this.event.getMetadata().getName()
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
                    this.event.getMetadata() != null ? this.event.getMetadata().getName() : "unknown");

            return null;

        } catch (Exception e) {
            log.error(
                    "Unexpected error while extracting container name from event: {}",
                    this.event != null && this.event.getMetadata() != null
                            ? this.event.getMetadata().getName()
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

        if (this.event != null && this.event.getInvolvedObject() != null) {
            String kind = this.event.getInvolvedObject().getKind();
            String name = this.event.getInvolvedObject().getName();
            String namespace = this.event.getInvolvedObject().getNamespace();

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
        if (this.event == null || this.event.getInvolvedObject() == null) {
            return false;
        }
        String fieldPath = this.event.getInvolvedObject().getFieldPath();
        if (fieldPath == null) {
            return false;
        }

        return fieldPath.toLowerCase().contains("initcontainer");
    }
}

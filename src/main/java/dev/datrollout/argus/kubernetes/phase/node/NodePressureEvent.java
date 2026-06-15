package dev.datrollout.argus.kubernetes.phase.node;

import java.time.Instant;
import java.util.Map;
import lombok.Builder;

@Builder
public record NodePressureEvent(
        String nodeName,
        PressureType pressureType,
        PressureStatus status,
        boolean pressureActive, // true = problem active, false = resolved
        String reason, // e.g. "KubeletHasSufficientMemory"
        String message, // human-readable from kubelet
        String lastTransitionTime, // ISO-8601 from k8s API
        String lastHeartbeatTime,
        Instant detectedAt,
        Map<String, String> nodeLabels // for zone/pool/role routing in hypothesis generation
        ) {
    public enum PressureType {
        MEMORY_PRESSURE,
        DISK_PRESSURE,
        PID_PRESSURE;

        public static PressureType fromConditionType(String type) {
            return switch (type) {
                case "MemoryPressure" -> MEMORY_PRESSURE;
                case "DiskPressure" -> DISK_PRESSURE;
                case "PIDPressure" -> PID_PRESSURE;
                default -> throw new IllegalArgumentException("Unknown pressure condition type: " + type);
            };
        }
    }

    public enum PressureStatus {
        TRUE, // pressure active
        FALSE, // pressure resolved
        UNKNOWN; // kubelet unresponsive

        public static PressureStatus fromConditionStatus(String status) {
            return switch (status) {
                case "True" -> TRUE;
                case "False" -> FALSE;
                case "Unknown" -> UNKNOWN;
                default -> throw new IllegalArgumentException("Unknown condition status: " + status);
            };
        }
    }
}

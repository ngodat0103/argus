package dev.datrollout.argus.kubernetes.detector;

import dev.datrollout.argus.kubernetes.phase.node.NodePressureEvent;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeCondition;
import io.fabric8.kubernetes.api.model.NodeStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

@Slf4j
// @Component // Temporary disable for now
@RequiredArgsConstructor
public class NodeMemoryWatcher extends AbstractKubernetesWatcher<Node> {

    private final ApplicationEventPublisher applicationEventPublisher;
    private final KubernetesClient kubernetesClient;
    private final ConcurrentHashMap<String, String> seenPressureStates = new ConcurrentHashMap<>();
    private static final List<String> PRESSURE_CONDITION_TYPES =
            List.of("MemoryPressure", "DiskPressure", "PIDPressure");

    @Override
    protected Watch startWatch() {
        return kubernetesClient.nodes().watch(this);
    }

    @Override
    public void eventReceived(Action action, Node node) {
        var nodeName = node.getMetadata().getName();

        log.debug("eventReceived: action={} node={}", action, nodeName);

        // ADDED fires on agent startup / informer resync — could re-publish stale
        // pressure states that are already resolved. Restrict to MODIFIED only.
        if (action != Action.MODIFIED) {
            log.debug("Skipping non-MODIFIED event: action={} node={}", action, nodeName);
            return;
        }

        var conditions = extractPressureConditions(node);
        log.debug("Pressure condition scan complete: node={} pressureConditionsFound={}", nodeName, conditions.size());

        if (conditions.isEmpty()) {
            log.debug("No pressure conditions found in node={}, skipping publish", nodeName);
            return;
        }

        conditions.forEach(condition -> processCondition(node, condition));
    }

    // -------------------------------------------------------------------------
    // Detection
    // -------------------------------------------------------------------------

    /**
     * Extracts all pressure-type conditions from the node.
     * Returns ALL matching conditions regardless of status — we need to track
     * both True (pressure active) and False (pressure resolved) transitions
     * so the agent can close out active incidents.
     */
    private List<NodeCondition> extractPressureConditions(Node node) {
        var nodeConditions = Optional.ofNullable(node.getStatus())
                .map(NodeStatus::getConditions)
                .orElse(List.of());

        if (nodeConditions.isEmpty()) {
            log.debug(
                    "extractPressureConditions: node={} has no conditions",
                    node.getMetadata().getName());
            return List.of();
        }

        log.debug(
                "extractPressureConditions: node={} totalConditions={}",
                node.getMetadata().getName(),
                nodeConditions.size());

        var hits = new ArrayList<NodeCondition>();
        for (var condition : nodeConditions) {
            boolean isPressureType = PRESSURE_CONDITION_TYPES.contains(condition.getType());
            log.debug(
                    "Condition check: node={} type={} status={} reason={} isPressureType={}",
                    node.getMetadata().getName(),
                    condition.getType(),
                    condition.getStatus(),
                    condition.getReason(),
                    isPressureType);
            if (isPressureType) {
                hits.add(condition);
            }
        }
        return hits;
    }

    private void processCondition(Node node, NodeCondition condition) {
        var nodeName = node.getMetadata().getName();
        var conditionType = condition.getType();
        var currentStatus = condition.getStatus(); // "True" | "False" | "Unknown"
        var key = pressureKey(nodeName, conditionType);
        var prev = seenPressureStates.put(key, currentStatus);

        log.debug(
                "Dedup check: node={} conditionType={} prevStatus={} currentStatus={}",
                nodeName,
                conditionType,
                prev,
                currentStatus);

        // Suppress identical status — heartbeat noise
        if (Objects.equals(prev, currentStatus)) {
            log.debug(
                    "Suppressed unchanged pressure state: node={} conditionType={} status={}",
                    nodeName,
                    conditionType,
                    currentStatus);
            return;
        }

        var pressureActive = "True".equals(currentStatus);
        var wasActive = "True".equals(prev);

        // --- Gate: only emit if pressure is active, or if it just resolved ---
        // prev==null means first observation for this node since agent start.
        // A False on first observation is healthy baseline — not an incident close.
        // Only emit False if we previously recorded True for this condition.
        if (!pressureActive && !wasActive) {
            log.debug(
                    "Suppressing False/Unknown on first observation or non-active transition: "
                            + "node={} conditionType={} prev={} current={}",
                    nodeName,
                    conditionType,
                    prev,
                    currentStatus);
            return;
        }

        // From here: either pressure just became active (True),
        // or it just resolved (False/Unknown) after a known-active state.
        var nodePressureEvent = buildPressureEvent(node, condition, pressureActive);
        if (pressureActive) {
            log.warn(
                    "Node pressure ACTIVE: node={} conditionType={} reason={} message={} lastTransition={}",
                    nodeName,
                    conditionType,
                    condition.getReason(),
                    condition.getMessage(),
                    condition.getLastTransitionTime());
        } else if ("False".equals(currentStatus)) {
            log.info(
                    "Node pressure RESOLVED: node={} conditionType={} lastTransition={}",
                    nodeName,
                    conditionType,
                    condition.getLastTransitionTime());
        } else {
            // Unknown after a True — kubelet went silent while pressure was active
            log.warn(
                    "Node pressure UNKNOWN (kubelet may be unresponsive while pressure was active): "
                            + "node={} conditionType={} lastHeartbeat={}",
                    nodeName,
                    conditionType,
                    condition.getLastHeartbeatTime());
        }

        log.debug(
                "Publishing NodePressureEventWrapper: node={} conditionType={} active={} event={}",
                nodeName,
                conditionType,
                pressureActive,
                nodePressureEvent);

        applicationEventPublisher.publishEvent(nodePressureEvent);

        log.debug("NodePressureEventWrapper published successfully: node={} conditionType={}", nodeName, conditionType);
    }

    // -------------------------------------------------------------------------
    // Event construction
    // -------------------------------------------------------------------------
    private NodePressureEvent buildPressureEvent(Node node, NodeCondition condition, boolean pressureActive) {
        var meta = node.getMetadata();
        var event = NodePressureEvent.builder()
                .nodeName(meta.getName())
                .pressureType(NodePressureEvent.PressureType.fromConditionType(condition.getType()))
                .status(NodePressureEvent.PressureStatus.fromConditionStatus(condition.getStatus()))
                .pressureActive(pressureActive)
                .reason(condition.getReason())
                .message(condition.getMessage())
                .lastTransitionTime(condition.getLastTransitionTime())
                .lastHeartbeatTime(condition.getLastHeartbeatTime())
                .detectedAt(Instant.now())
                .nodeLabels(meta.getLabels() != null ? Map.copyOf(meta.getLabels()) : Map.of())
                .build();
        log.debug("Built NodePressureEvent: {}", event);
        return event;
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String pressureKey(String nodeName, String conditionType) {
        return nodeName + "/" + conditionType;
    }
}

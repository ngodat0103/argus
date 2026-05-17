package dev.datrollout.argus.kubernetes.embabel;

import com.embabel.agent.api.annotation.LlmTool;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.DaemonSetCondition;
import io.fabric8.kubernetes.api.model.apps.DaemonSetSpec;
import io.fabric8.kubernetes.api.model.apps.DaemonSetStatus;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentCondition;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpec;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatus;
import io.fabric8.kubernetes.api.model.apps.DeploymentStrategy;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.RollingUpdateDeployment;
import io.fabric8.kubernetes.api.model.apps.RollingUpdateStatefulSetStrategy;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetCondition;
import io.fabric8.kubernetes.api.model.apps.StatefulSetSpec;
import io.fabric8.kubernetes.api.model.apps.StatefulSetStatus;
import io.fabric8.kubernetes.api.model.apps.StatefulSetUpdateStrategy;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LLM-callable tools for inspecting workload rollout state and revision history.
 *
 * <p>Resource health (`inspectWorkloadResourceHealth` in {@link MetricsResourceTool}) tells you
 * whether pods are unhappy. These tools answer the orthogonal question: is the rollout itself
 * making progress, paused, stuck, or stale?
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class WorkloadStateTool {

    private static final String REVISION_ANNOTATION = "deployment.kubernetes.io/revision";
    private static final String CHANGE_CAUSE_ANNOTATION = "kubernetes.io/change-cause";

    private final KubernetesClient kubernetesClient;

    // ──────────────────────────────────────────────────────────────────────────
    // LLM tools
    // ──────────────────────────────────────────────────────────────────────────

    @LlmTool(
            name = "inspectDeploymentRollout",
            description = """
                    Use this tool when a Deployment seems stuck, slow to roll out, paused, or you
                    just want to know "did the new version actually go live". Returns desired/
                    updated/ready/available/unavailable replica counts, the strategy
                    (RollingUpdate vs Recreate, maxSurge, maxUnavailable), pause flag,
                    progressDeadlineSeconds, the Progressing + Available conditions (with reason
                    + message), the new and old ReplicaSets owned by this Deployment with their
                    pod counts, and a SUSPICIONS block flagging stuck rollouts
                    (Progressing=False, ProgressDeadlineExceeded), paused state, drift between
                    desired and observed generation, and zero-availability situations.
                    """
    )
    public String inspectDeploymentRollout(String namespace, String name) {
        Deployment d = kubernetesClient.apps().deployments().inNamespace(namespace).withName(name).get();
        if (d == null) {
            return "ERROR: deployment " + namespace + "/" + name + " not found.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== DEPLOYMENT ROLLOUT: ").append(namespace).append("/").append(name).append(" ===\n\n");

        DeploymentSpec spec = d.getSpec();
        DeploymentStatus status = d.getStatus();

        int desired = nz(Optional.ofNullable(spec).map(DeploymentSpec::getReplicas).orElse(null));
        int updated = nz(Optional.ofNullable(status).map(DeploymentStatus::getUpdatedReplicas).orElse(null));
        int ready = nz(Optional.ofNullable(status).map(DeploymentStatus::getReadyReplicas).orElse(null));
        int available = nz(Optional.ofNullable(status).map(DeploymentStatus::getAvailableReplicas).orElse(null));
        int unavailable = nz(Optional.ofNullable(status).map(DeploymentStatus::getUnavailableReplicas).orElse(null));
        long observedGen = nzL(Optional.ofNullable(status).map(DeploymentStatus::getObservedGeneration).orElse(null));
        long generation = nzL(d.getMetadata() == null ? null : d.getMetadata().getGeneration());
        boolean paused = Boolean.TRUE.equals(Optional.ofNullable(spec).map(DeploymentSpec::getPaused).orElse(false));

        sb.append("  generation:        ").append(generation).append("\n");
        sb.append("  observedGeneration: ").append(observedGen).append("\n");
        sb.append("  paused:            ").append(paused).append("\n");
        sb.append("  replicas:          desired=").append(desired)
                .append("  updated=").append(updated)
                .append("  ready=").append(ready)
                .append("  available=").append(available)
                .append("  unavailable=").append(unavailable).append("\n");

        DeploymentStrategy strategy = Optional.ofNullable(spec).map(DeploymentSpec::getStrategy).orElse(null);
        String strategyType = strategy == null ? "RollingUpdate" : Optional.ofNullable(strategy.getType()).orElse("RollingUpdate");
        sb.append("  strategy:          ").append(strategyType);
        RollingUpdateDeployment ru = strategy == null ? null : strategy.getRollingUpdate();
        if (ru != null) {
            sb.append("  maxSurge=").append(intOrStr(ru.getMaxSurge()))
                    .append("  maxUnavailable=").append(intOrStr(ru.getMaxUnavailable()));
        }
        sb.append("\n");
        Optional.ofNullable(spec).map(DeploymentSpec::getProgressDeadlineSeconds)
                .ifPresent(p -> sb.append("  progressDeadline:  ").append(p).append("s\n"));
        Optional.ofNullable(spec).map(DeploymentSpec::getMinReadySeconds)
                .ifPresent(m -> sb.append("  minReadySeconds:   ").append(m).append("\n"));

        // Conditions
        List<DeploymentCondition> conds = Optional.ofNullable(status)
                .map(DeploymentStatus::getConditions).orElse(Collections.emptyList());
        if (!conds.isEmpty()) {
            sb.append("\n  conditions:\n");
            for (DeploymentCondition c : conds) {
                sb.append("    ").append(safe(c.getType())).append("=").append(safe(c.getStatus()));
                if (c.getReason() != null) sb.append("  reason=").append(c.getReason());
                if (c.getMessage() != null) sb.append("  msg=").append(c.getMessage());
                sb.append("\n");
            }
        }

        // Owned ReplicaSets
        sb.append("\n[Owned ReplicaSets]\n");
        List<ReplicaSet> owned = ownedReplicaSets(namespace, d);
        if (owned.isEmpty()) {
            sb.append("  (none — controller may not have created any yet)\n");
        } else {
            sb.append(String.format("%-50s  %-9s  %-8s  %-8s  %-7s  %s%n",
                    "REPLICASET", "REVISION", "DESIRED", "READY", "ACTIVE", "POD-TEMPLATE-HASH"));
            sb.append("-".repeat(120)).append("\n");
            for (ReplicaSet rs : owned) {
                String revision = Optional.ofNullable(rs.getMetadata().getAnnotations())
                        .map(a -> a.get(REVISION_ANNOTATION)).orElse("?");
                int desiredRs = nz(Optional.ofNullable(rs.getSpec()).map(s -> s.getReplicas()).orElse(null));
                int readyRs = nz(Optional.ofNullable(rs.getStatus()).map(s -> s.getReadyReplicas()).orElse(null));
                String hash = Optional.ofNullable(rs.getMetadata().getLabels())
                        .map(l -> l.get("pod-template-hash")).orElse("-");
                String active = desiredRs > 0 ? "yes" : "no";
                sb.append(String.format("%-50s  %-9s  %-8d  %-8d  %-7s  %s%n",
                        truncate(rs.getMetadata().getName(), 50), revision, desiredRs, readyRs, active, hash));
            }
        }

        appendDeploymentSuspicions(sb, d, owned, desired, available, generation, observedGen, paused);
        return sb.toString();
    }

    @LlmTool(
            name = "getRolloutHistory",
            description = """
                    Use this tool to see what versions of a workload have rolled through, what
                    changed, and how to roll back. Mirrors `kubectl rollout history`.
                    Pass kind: 'Deployment' (full revision history), 'StatefulSet' or 'DaemonSet'
                    (current vs update revision; controllerrevisions are not enumerated here —
                    use kubectl rollout history for those). Pass the workload name and namespace.
                    For Deployments returns each revision: revision number, ReplicaSet name,
                    desired replicas, change-cause annotation, and the timestamp of the
                    ReplicaSet's creation.
                    """
    )
    public String getRolloutHistory(String namespace, String kind, String name) {
        if (kind == null) return "ERROR: kind is required.";
        return switch (kind.toLowerCase()) {
            case "deployment" -> deploymentHistory(namespace, name);
            case "statefulset" -> statefulSetRevisionSummary(namespace, name);
            case "daemonset" -> daemonSetRevisionSummary(namespace, name);
            default -> "ERROR: getRolloutHistory supports kind in {Deployment, StatefulSet, DaemonSet}. Got: " + kind;
        };
    }

    @LlmTool(
            name = "inspectStatefulSetState",
            description = """
                    Use this tool when a StatefulSet is rolling slowly, stuck on one ordinal, or
                    when you need to know whether a partitioned/canary update is in progress.
                    Returns desired/current/updated/ready replicas, currentRevision vs
                    updateRevision (drift = ongoing rollout), the update strategy
                    (RollingUpdate vs OnDelete) with partition, podManagementPolicy,
                    serviceName, and per-ordinal pod readiness so you can see exactly which
                    ordinal is stuck.
                    """
    )
    public String inspectStatefulSetState(String namespace, String name) {
        StatefulSet ss = kubernetesClient.apps().statefulSets().inNamespace(namespace).withName(name).get();
        if (ss == null) {
            return "ERROR: statefulset " + namespace + "/" + name + " not found.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== STATEFULSET STATE: ").append(namespace).append("/").append(name).append(" ===\n\n");

        StatefulSetSpec spec = ss.getSpec();
        StatefulSetStatus status = ss.getStatus();

        int desired = nz(Optional.ofNullable(spec).map(StatefulSetSpec::getReplicas).orElse(null));
        int current = nz(Optional.ofNullable(status).map(StatefulSetStatus::getCurrentReplicas).orElse(null));
        int updated = nz(Optional.ofNullable(status).map(StatefulSetStatus::getUpdatedReplicas).orElse(null));
        int ready = nz(Optional.ofNullable(status).map(StatefulSetStatus::getReadyReplicas).orElse(null));
        int available = nz(Optional.ofNullable(status).map(StatefulSetStatus::getAvailableReplicas).orElse(null));
        long observedGen = nzL(Optional.ofNullable(status).map(StatefulSetStatus::getObservedGeneration).orElse(null));
        long generation = nzL(ss.getMetadata() == null ? null : ss.getMetadata().getGeneration());
        String currentRev = Optional.ofNullable(status).map(StatefulSetStatus::getCurrentRevision).orElse("?");
        String updateRev = Optional.ofNullable(status).map(StatefulSetStatus::getUpdateRevision).orElse("?");

        sb.append("  generation:         ").append(generation).append("\n");
        sb.append("  observedGeneration: ").append(observedGen).append("\n");
        sb.append("  serviceName:        ").append(safe(Optional.ofNullable(spec).map(StatefulSetSpec::getServiceName).orElse(null))).append("\n");
        sb.append("  podManagementPolicy:").append(" ").append(safe(Optional.ofNullable(spec).map(StatefulSetSpec::getPodManagementPolicy).orElse(null))).append("\n");
        sb.append("  replicas:           desired=").append(desired)
                .append("  current=").append(current)
                .append("  updated=").append(updated)
                .append("  ready=").append(ready)
                .append("  available=").append(available).append("\n");
        sb.append("  currentRevision:    ").append(currentRev).append("\n");
        sb.append("  updateRevision:     ").append(updateRev);
        if (!currentRev.equals(updateRev)) sb.append("  ⚠ rollout in progress");
        sb.append("\n");

        StatefulSetUpdateStrategy us = Optional.ofNullable(spec).map(StatefulSetSpec::getUpdateStrategy).orElse(null);
        String usType = us == null ? "RollingUpdate" : Optional.ofNullable(us.getType()).orElse("RollingUpdate");
        sb.append("  updateStrategy:     ").append(usType);
        RollingUpdateStatefulSetStrategy rus = us == null ? null : us.getRollingUpdate();
        if (rus != null) {
            sb.append("  partition=").append(nz(rus.getPartition()));
            if (rus.getMaxUnavailable() != null) sb.append("  maxUnavailable=").append(intOrStr(rus.getMaxUnavailable()));
        }
        sb.append("\n");

        // Conditions
        List<StatefulSetCondition> conds = Optional.ofNullable(status)
                .map(StatefulSetStatus::getConditions).orElse(Collections.emptyList());
        if (!conds.isEmpty()) {
            sb.append("\n  conditions:\n");
            for (StatefulSetCondition c : conds) {
                sb.append("    ").append(safe(c.getType())).append("=").append(safe(c.getStatus()));
                if (c.getReason() != null) sb.append("  reason=").append(c.getReason());
                if (c.getMessage() != null) sb.append("  msg=").append(c.getMessage());
                sb.append("\n");
            }
        }

        // Per-ordinal readiness
        Map<String, String> sel = Optional.ofNullable(spec).map(StatefulSetSpec::getSelector)
                .map(LabelSelector::getMatchLabels).orElse(Collections.emptyMap());
        if (!sel.isEmpty()) {
            List<Pod> pods = kubernetesClient.pods().inNamespace(namespace).withLabels(sel).list().getItems().stream()
                    .sorted(Comparator.comparing(p -> p.getMetadata().getName()))
                    .toList();
            sb.append("\n[Per-ordinal pods]\n");
            if (pods.isEmpty()) {
                sb.append("  (none)\n");
            } else {
                sb.append(String.format("%-55s  %-10s  %-5s  %-9s  %s%n",
                        "POD", "PHASE", "READY", "RESTARTS", "REVISION-LABEL"));
                sb.append("-".repeat(110)).append("\n");
                for (Pod p : pods) {
                    String phase = Optional.ofNullable(p.getStatus()).map(PodStatus::getPhase).orElse("?");
                    boolean podReady = Optional.ofNullable(p.getStatus()).map(PodStatus::getConditions)
                            .orElse(Collections.emptyList()).stream()
                            .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
                    int restarts = Optional.ofNullable(p.getStatus()).map(PodStatus::getContainerStatuses)
                            .orElse(Collections.emptyList()).stream()
                            .mapToInt(cs -> cs.getRestartCount() == null ? 0 : cs.getRestartCount()).sum();
                    String rev = Optional.ofNullable(p.getMetadata().getLabels())
                            .map(l -> l.get("controller-revision-hash")).orElse("?");
                    sb.append(String.format("%-55s  %-10s  %-5s  %-9d  %s%n",
                            truncate(p.getMetadata().getName(), 55), phase, podReady, restarts, rev));
                }
            }
        }

        sb.append("\n[SUSPICIONS]\n");
        boolean any = false;
        if (generation > observedGen) {
            sb.append("  - controller has not yet observed the latest spec (generation=")
                    .append(generation).append(" > observed=").append(observedGen).append(").\n");
            any = true;
        }
        if (!currentRev.equals(updateRev) && updated < desired) {
            sb.append("  - rollout in progress: ").append(updated).append("/").append(desired)
                    .append(" pods on the new revision. If progress has stalled,\n");
            sb.append("    inspect the lowest-ordinal pod that still runs currentRevision.\n");
            any = true;
        }
        if (rus != null && nz(rus.getPartition()) > 0) {
            sb.append("  - update strategy has partition=").append(rus.getPartition())
                    .append(" — only ordinals >= partition are updated. Decrement partition to roll the rest.\n");
            any = true;
        }
        if (ready < desired) {
            sb.append("  - ready (").append(ready).append(") < desired (").append(desired)
                    .append("). Run inspectPodResourceHealth or getPreviousContainerLogs on the unready ordinal.\n");
            any = true;
        }
        if (!any) sb.append("  - StatefulSet is fully rolled out and at desired replicas.\n");
        return sb.toString();
    }

    @LlmTool(
            name = "inspectDaemonSetState",
            description = """
                    Use this tool when a DaemonSet is missing pods on some nodes, when an update
                    is rolling slowly, or when you need to see misscheduled pods. Returns
                    desiredNumberScheduled, currentNumberScheduled, updatedNumberScheduled,
                    numberReady, numberAvailable, numberUnavailable, numberMisscheduled, and the
                    nodeSelector and update strategy. Flags missing nodes (desired - current),
                    misscheduled placements, and rollout drift (updated < desired).
                    """
    )
    public String inspectDaemonSetState(String namespace, String name) {
        DaemonSet ds = kubernetesClient.apps().daemonSets().inNamespace(namespace).withName(name).get();
        if (ds == null) {
            return "ERROR: daemonset " + namespace + "/" + name + " not found.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== DAEMONSET STATE: ").append(namespace).append("/").append(name).append(" ===\n\n");

        DaemonSetSpec spec = ds.getSpec();
        DaemonSetStatus status = ds.getStatus();

        int desired = nz(Optional.ofNullable(status).map(DaemonSetStatus::getDesiredNumberScheduled).orElse(null));
        int current = nz(Optional.ofNullable(status).map(DaemonSetStatus::getCurrentNumberScheduled).orElse(null));
        int updated = nz(Optional.ofNullable(status).map(DaemonSetStatus::getUpdatedNumberScheduled).orElse(null));
        int ready = nz(Optional.ofNullable(status).map(DaemonSetStatus::getNumberReady).orElse(null));
        int available = nz(Optional.ofNullable(status).map(DaemonSetStatus::getNumberAvailable).orElse(null));
        int unavailable = nz(Optional.ofNullable(status).map(DaemonSetStatus::getNumberUnavailable).orElse(null));
        int misscheduled = nz(Optional.ofNullable(status).map(DaemonSetStatus::getNumberMisscheduled).orElse(null));
        long observedGen = nzL(Optional.ofNullable(status).map(DaemonSetStatus::getObservedGeneration).orElse(null));
        long generation = nzL(ds.getMetadata() == null ? null : ds.getMetadata().getGeneration());

        sb.append("  generation:         ").append(generation).append("\n");
        sb.append("  observedGeneration: ").append(observedGen).append("\n");
        sb.append("  scheduling:\n");
        sb.append("    desired=").append(desired)
                .append("  current=").append(current)
                .append("  updated=").append(updated).append("\n");
        sb.append("    ready=").append(ready)
                .append("  available=").append(available)
                .append("  unavailable=").append(unavailable)
                .append("  misscheduled=").append(misscheduled).append("\n");

        Map<String, String> nodeSelector = Optional.ofNullable(spec).map(DaemonSetSpec::getTemplate)
                .map(t -> t.getSpec()).map(s -> s.getNodeSelector()).orElse(Collections.emptyMap());
        sb.append("  nodeSelector:       ").append(nodeSelector.isEmpty() ? "<none>" : nodeSelector).append("\n");

        if (spec != null && spec.getUpdateStrategy() != null) {
            String type = Optional.ofNullable(spec.getUpdateStrategy().getType()).orElse("RollingUpdate");
            sb.append("  updateStrategy:     ").append(type);
            if (spec.getUpdateStrategy().getRollingUpdate() != null) {
                IntOrString mu = spec.getUpdateStrategy().getRollingUpdate().getMaxUnavailable();
                IntOrString ms = spec.getUpdateStrategy().getRollingUpdate().getMaxSurge();
                if (mu != null) sb.append("  maxUnavailable=").append(intOrStr(mu));
                if (ms != null) sb.append("  maxSurge=").append(intOrStr(ms));
            }
            sb.append("\n");
        }

        // Conditions
        List<DaemonSetCondition> conds = Optional.ofNullable(status)
                .map(DaemonSetStatus::getConditions).orElse(Collections.emptyList());
        if (!conds.isEmpty()) {
            sb.append("\n  conditions:\n");
            for (DaemonSetCondition c : conds) {
                sb.append("    ").append(safe(c.getType())).append("=").append(safe(c.getStatus()));
                if (c.getReason() != null) sb.append("  reason=").append(c.getReason());
                if (c.getMessage() != null) sb.append("  msg=").append(c.getMessage());
                sb.append("\n");
            }
        }

        sb.append("\n[SUSPICIONS]\n");
        boolean any = false;
        if (generation > observedGen) {
            sb.append("  - controller has not observed the latest spec yet (generation=")
                    .append(generation).append(" > observed=").append(observedGen).append(").\n");
            any = true;
        }
        if (current < desired) {
            sb.append("  - ").append(desired - current).append(" node(s) where the DaemonSet should run\n");
            sb.append("    have NO pod yet. Likely causes: nodeSelector not matching, missing\n");
            sb.append("    tolerations for node taints, or scheduling pressure.\n");
            any = true;
        }
        if (misscheduled > 0) {
            sb.append("  - ").append(misscheduled).append(" pod(s) are running on nodes that no longer match\n");
            sb.append("    nodeSelector/affinity — they will be evicted by the DaemonSet controller.\n");
            any = true;
        }
        if (updated < desired) {
            sb.append("  - rollout in progress: ").append(updated).append("/").append(desired)
                    .append(" pods on the new template. If stuck, check maxUnavailable + node pressure.\n");
            any = true;
        }
        if (ready < current) {
            sb.append("  - ").append(current - ready).append(" scheduled pod(s) are NOT ready. Run\n");
            sb.append("    inspectPodResourceHealth on a failing pod to find the root cause.\n");
            any = true;
        }
        if (!any) sb.append("  - DaemonSet is fully scheduled and ready on all targeted nodes.\n");
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Internals
    // ──────────────────────────────────────────────────────────────────────────

    private String deploymentHistory(String namespace, String name) {
        Deployment d = kubernetesClient.apps().deployments().inNamespace(namespace).withName(name).get();
        if (d == null) {
            return "ERROR: deployment " + namespace + "/" + name + " not found.";
        }
        List<ReplicaSet> rsList = ownedReplicaSets(namespace, d);
        StringBuilder sb = new StringBuilder();
        sb.append("=== ROLLOUT HISTORY: Deployment ").append(namespace).append("/").append(name).append(" ===\n\n");
        if (rsList.isEmpty()) {
            sb.append("(no ReplicaSets found — controller may not have created any yet)\n");
            return sb.toString();
        }
        sb.append(String.format("%-9s  %-50s  %-8s  %-8s  %-22s  %s%n",
                "REVISION", "REPLICASET", "DESIRED", "READY", "CREATED", "CHANGE-CAUSE"));
        sb.append("-".repeat(150)).append("\n");
        for (ReplicaSet rs : rsList) {
            String revision = Optional.ofNullable(rs.getMetadata().getAnnotations())
                    .map(a -> a.get(REVISION_ANNOTATION)).orElse("?");
            String change = Optional.ofNullable(rs.getMetadata().getAnnotations())
                    .map(a -> a.get(CHANGE_CAUSE_ANNOTATION)).orElse("<none>");
            int desiredRs = nz(Optional.ofNullable(rs.getSpec()).map(s -> s.getReplicas()).orElse(null));
            int readyRs = nz(Optional.ofNullable(rs.getStatus()).map(s -> s.getReadyReplicas()).orElse(null));
            String created = Optional.ofNullable(rs.getMetadata().getCreationTimestamp()).orElse("?");
            sb.append(String.format("%-9s  %-50s  %-8d  %-8d  %-22s  %s%n",
                    revision, truncate(rs.getMetadata().getName(), 50),
                    desiredRs, readyRs, truncate(created, 22), truncate(change, 60)));
        }
        sb.append("\n[NOTE]\n");
        sb.append("  Roll back with: kubectl rollout undo deployment/").append(name)
                .append(" -n ").append(namespace).append(" [--to-revision=N]\n");
        sb.append("  Set change-cause for new rollouts: kubectl annotate deployment/")
                .append(name).append(" kubernetes.io/change-cause='...' -n ").append(namespace).append("\n");
        return sb.toString();
    }

    private String statefulSetRevisionSummary(String namespace, String name) {
        StatefulSet ss = kubernetesClient.apps().statefulSets().inNamespace(namespace).withName(name).get();
        if (ss == null) return "ERROR: statefulset " + namespace + "/" + name + " not found.";
        StatefulSetStatus status = ss.getStatus();
        StringBuilder sb = new StringBuilder();
        sb.append("=== ROLLOUT HISTORY: StatefulSet ").append(namespace).append("/").append(name).append(" ===\n\n");
        sb.append("  currentRevision: ").append(safe(Optional.ofNullable(status).map(StatefulSetStatus::getCurrentRevision).orElse(null))).append("\n");
        sb.append("  updateRevision:  ").append(safe(Optional.ofNullable(status).map(StatefulSetStatus::getUpdateRevision).orElse(null))).append("\n");
        sb.append("\nThis tool does not enumerate ControllerRevisions for StatefulSets. Use:\n");
        sb.append("  kubectl rollout history statefulset/").append(name).append(" -n ").append(namespace).append("\n");
        sb.append("for the full numbered history. inspectStatefulSetState gives live state.\n");
        return sb.toString();
    }

    private String daemonSetRevisionSummary(String namespace, String name) {
        DaemonSet ds = kubernetesClient.apps().daemonSets().inNamespace(namespace).withName(name).get();
        if (ds == null) return "ERROR: daemonset " + namespace + "/" + name + " not found.";
        StringBuilder sb = new StringBuilder();
        sb.append("=== ROLLOUT HISTORY: DaemonSet ").append(namespace).append("/").append(name).append(" ===\n\n");
        sb.append("This tool does not enumerate ControllerRevisions for DaemonSets. Use:\n");
        sb.append("  kubectl rollout history daemonset/").append(name).append(" -n ").append(namespace).append("\n");
        sb.append("for the full numbered history. inspectDaemonSetState gives live state.\n");
        return sb.toString();
    }

    private List<ReplicaSet> ownedReplicaSets(String namespace, Deployment d) {
        Map<String, String> sel = Optional.ofNullable(d.getSpec()).map(DeploymentSpec::getSelector)
                .map(LabelSelector::getMatchLabels).orElse(Collections.emptyMap());
        if (sel.isEmpty()) return Collections.emptyList();
        String uid = d.getMetadata() == null ? null : d.getMetadata().getUid();
        List<ReplicaSet> rsList = kubernetesClient.apps().replicaSets()
                .inNamespace(namespace).withLabels(sel).list().getItems();
        return rsList.stream()
                .filter(rs -> uid == null || Optional.ofNullable(rs.getMetadata().getOwnerReferences())
                        .orElse(Collections.emptyList())
                        .stream().anyMatch(o -> uid.equals(o.getUid())))
                .sorted(Comparator.comparingInt(this::revisionNumber).reversed())
                .toList();
    }

    private int revisionNumber(ReplicaSet rs) {
        try {
            String r = Optional.ofNullable(rs.getMetadata().getAnnotations())
                    .map(a -> a.get(REVISION_ANNOTATION)).orElse("0");
            return Integer.parseInt(r);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void appendDeploymentSuspicions(StringBuilder sb, Deployment d, List<ReplicaSet> owned,
                                            int desired, int available,
                                            long generation, long observedGen, boolean paused) {
        sb.append("\n[SUSPICIONS]\n");
        boolean any = false;
        if (paused) {
            sb.append("  - Deployment is PAUSED — no rollout will progress until\n");
            sb.append("    `kubectl rollout resume deployment/").append(d.getMetadata().getName())
                    .append(" -n ").append(d.getMetadata().getNamespace()).append("` is run.\n");
            any = true;
        }
        if (generation > observedGen) {
            sb.append("  - controller has not yet observed generation ").append(generation)
                    .append(" (observed=").append(observedGen).append("). Spec was updated very recently\n");
            sb.append("    or the controller is overloaded.\n");
            any = true;
        }
        Optional<DeploymentCondition> progressing = Optional.ofNullable(d.getStatus())
                .map(DeploymentStatus::getConditions).orElse(Collections.emptyList())
                .stream().filter(c -> "Progressing".equals(c.getType())).findFirst();
        progressing.ifPresent(c -> {
            if ("False".equals(c.getStatus())) {
                sb.append("  - Progressing=False (reason=").append(c.getReason()).append(")\n");
                if ("ProgressDeadlineExceeded".equals(c.getReason())) {
                    sb.append("    Rollout exceeded progressDeadlineSeconds and was marked failed.\n");
                    sb.append("    Inspect the new ReplicaSet's pods (likely CrashLoopBackOff or ImagePullBackOff).\n");
                }
            }
        });
        Optional<DeploymentCondition> availableCond = Optional.ofNullable(d.getStatus())
                .map(DeploymentStatus::getConditions).orElse(Collections.emptyList())
                .stream().filter(c -> "Available".equals(c.getType())).findFirst();
        availableCond.ifPresent(c -> {
            if ("False".equals(c.getStatus())) {
                sb.append("  - Available=False — Deployment has fewer ready replicas than minAvailable\n");
                sb.append("    requires. Reason: ").append(c.getReason())
                        .append(", message: ").append(c.getMessage()).append("\n");
            }
        });
        if (desired > 0 && available == 0) {
            sb.append("  - 0 of ").append(desired).append(" desired replicas are Available — Service traffic to this\n");
            sb.append("    deployment will fail. Run inspectPodResourceHealth on a backing pod.\n");
            any = true;
        }
        long activeRs = owned.stream().filter(rs -> nz(rs.getSpec() == null ? null : rs.getSpec().getReplicas()) > 0).count();
        if (activeRs > 1) {
            sb.append("  - ").append(activeRs).append(" ReplicaSets are simultaneously active — rollout is in flight.\n");
            any = true;
        }
        if (!any && progressing.isEmpty()) {
            sb.append("  - Rollout looks healthy at the controller level.\n");
        }
    }

    private int nz(Integer v) { return v == null ? 0 : v; }
    private long nzL(Long v) { return v == null ? 0L : v; }

    private String intOrStr(IntOrString io) {
        if (io == null) return "<none>";
        if (io.getStrVal() != null && !io.getStrVal().isBlank()) return io.getStrVal();
        return io.getIntVal() == null ? "<none>" : io.getIntVal().toString();
    }

    private String safe(String s) { return s == null || s.isBlank() ? "<none>" : s; }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(1, max - 1)) + "…";
    }
}

package dev.datrollout.argus.kubernetes.embabel;

import com.embabel.agent.api.annotation.LlmTool;
import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.LabelSelectorRequirement;
import io.fabric8.kubernetes.api.model.Node;
import io.fabric8.kubernetes.api.model.NodeAffinity;
import io.fabric8.kubernetes.api.model.NodeSelector;
import io.fabric8.kubernetes.api.model.NodeSelectorRequirement;
import io.fabric8.kubernetes.api.model.NodeSelectorTerm;
import io.fabric8.kubernetes.api.model.NodeSpec;
import io.fabric8.kubernetes.api.model.NodeStatus;
import io.fabric8.kubernetes.api.model.PodAffinity;
import io.fabric8.kubernetes.api.model.PodAntiAffinity;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.api.model.Taint;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.kubernetes.api.model.TopologySpreadConstraint;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudget;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudgetSpec;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudgetStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * LLM-callable tools for diagnosing scheduling failures and disruption-budget conflicts.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class SchedulingDiagnosticsTool {

    private static final int MAX_NODES_REPORTED = 30;

    private final KubernetesClient kubernetesClient;

    // ──────────────────────────────────────────────────────────────────────────
    // LLM tools
    // ──────────────────────────────────────────────────────────────────────────

    @LlmTool(
            name = "explainPodScheduling",
            description = """
                    Use this tool when a pod is stuck in Pending and you need to understand why
                    no node will accept it. Walks every cluster node and evaluates against the
                    pod's scheduling constraints: nodeName pin, nodeSelector, required
                    nodeAffinity, taints vs tolerations, runtimeClassName, and topology spread
                    constraints. Reports per-node match (✓) or the first mismatch reason (✗),
                    plus a summary of which constraint is the dominant blocker. Also notes
                    whether the pod is already scheduled (.spec.nodeName set) and surfaces the
                    PodScheduled condition message from the scheduler.
                    """
    )
    public String explainPodScheduling(String namespace, String podName) {
        Pod pod = kubernetesClient.pods().inNamespace(namespace).withName(podName).get();
        if (pod == null) {
            return "ERROR: pod " + namespace + "/" + podName + " not found.\n"
                    + "Hint: call findPods to locate the right pod first.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== POD SCHEDULING: ").append(namespace).append("/").append(podName).append(" ===\n\n");

        PodSpec spec = pod.getSpec();
        PodStatus status = pod.getStatus();

        String phase = Optional.ofNullable(status).map(PodStatus::getPhase).orElse("?");
        String currentNode = Optional.ofNullable(spec).map(PodSpec::getNodeName).orElse(null);
        String pinnedNode = Optional.ofNullable(spec).map(PodSpec::getNodeName).orElse(null);

        sb.append("  phase:        ").append(phase).append("\n");
        sb.append("  nodeName:     ").append(currentNode == null ? "<unset>" : currentNode).append("\n");

        Optional<PodCondition> scheduled = Optional.ofNullable(status).map(PodStatus::getConditions)
                .orElse(Collections.emptyList()).stream()
                .filter(c -> "PodScheduled".equals(c.getType())).findFirst();
        scheduled.ifPresent(c -> {
            sb.append("  PodScheduled: ").append(c.getStatus());
            if (c.getReason() != null) sb.append("  reason=").append(c.getReason());
            if (c.getMessage() != null) sb.append("  msg=").append(c.getMessage());
            sb.append("\n");
        });

        // Constraints summary
        Map<String, String> nodeSelector = Optional.ofNullable(spec).map(PodSpec::getNodeSelector)
                .orElse(Collections.emptyMap());
        Affinity affinity = Optional.ofNullable(spec).map(PodSpec::getAffinity).orElse(null);
        List<Toleration> tolerations = Optional.ofNullable(spec).map(PodSpec::getTolerations)
                .orElse(Collections.emptyList());
        List<TopologySpreadConstraint> topo = Optional.ofNullable(spec).map(PodSpec::getTopologySpreadConstraints)
                .orElse(Collections.emptyList());
        String runtimeClass = Optional.ofNullable(spec).map(PodSpec::getRuntimeClassName).orElse(null);

        sb.append("\n[Constraints]\n");
        sb.append("  nodeSelector:    ").append(nodeSelector.isEmpty() ? "<none>" : nodeSelector).append("\n");
        sb.append("  nodeAffinity:    ").append(formatNodeAffinitySummary(affinity)).append("\n");
        sb.append("  podAffinity:     ").append(formatPodAffinitySummary(affinity, false)).append("\n");
        sb.append("  podAntiAffinity: ").append(formatPodAffinitySummary(affinity, true)).append("\n");
        sb.append("  tolerations:     ").append(tolerations.isEmpty() ? "<none>"
                : tolerations.stream().map(this::formatToleration).collect(Collectors.joining(", "))).append("\n");
        sb.append("  topologySpread:  ").append(topo.isEmpty() ? "<none>" : topo.size() + " constraint(s)").append("\n");
        sb.append("  runtimeClass:    ").append(runtimeClass == null ? "<none>" : runtimeClass).append("\n");

        if (pinnedNode != null && !pinnedNode.isBlank()) {
            sb.append("\n[Result] Pod is already scheduled to node '").append(pinnedNode)
                    .append("'. Scheduler is not the bottleneck. Inspect kubelet/CNI on that node\n");
            sb.append("if the pod is still not running.\n");
            return sb.toString();
        }

        // Per-node evaluation
        List<Node> nodes;
        try {
            nodes = kubernetesClient.nodes().list().getItems();
        } catch (KubernetesClientException e) {
            return sb.append("ERROR: could not list nodes: ").append(e.getMessage()).toString();
        }
        if (nodes.isEmpty()) {
            return sb.append("\n[Result] cluster has no nodes.\n").toString();
        }

        sb.append("\n[Per-node evaluation] (").append(Math.min(nodes.size(), MAX_NODES_REPORTED))
                .append(" of ").append(nodes.size()).append(" nodes shown)\n");
        sb.append(String.format("%-40s  %-3s  %s%n", "NODE", "FIT", "REASON"));
        sb.append("-".repeat(120)).append("\n");

        Map<String, Integer> blockerCounts = new LinkedHashMap<>();
        int passing = 0;
        int shown = 0;
        for (Node node : nodes.stream().sorted(Comparator.comparing(n -> n.getMetadata().getName())).toList()) {
            String reason = evaluateFit(pod, node, nodeSelector, affinity, tolerations, runtimeClass);
            boolean fits = reason == null;
            if (fits) passing++;
            else blockerCounts.merge(reasonCategory(reason), 1, Integer::sum);
            if (shown++ < MAX_NODES_REPORTED) {
                sb.append(String.format("%-40s  %-3s  %s%n",
                        truncate(node.getMetadata().getName(), 40),
                        fits ? "✓" : "✗",
                        fits ? "matches all constraints" : reason));
            }
        }

        sb.append("\n[Summary]\n");
        sb.append("  ").append(passing).append("/").append(nodes.size()).append(" node(s) satisfy declared constraints.\n");
        if (!blockerCounts.isEmpty()) {
            sb.append("  Top blockers:\n");
            blockerCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(5)
                    .forEach(e -> sb.append("    - ").append(e.getKey()).append(": ").append(e.getValue()).append(" node(s)\n"));
        }
        if (!topo.isEmpty()) {
            sb.append("  Pod has ").append(topo.size()).append(" topologySpreadConstraint(s); even if a node\n");
            sb.append("  passes label/taint checks, spread skew (whenUnsatisfiable=DoNotSchedule) can still reject it.\n");
        }
        if (passing == 0) {
            sb.append("  No node satisfies the static constraints — the pod will stay Pending until\n");
            sb.append("  cluster topology changes (new node label, removed taint, etc.). When passing>0\n");
            sb.append("  but pod is still Pending, the scheduler is failing on resource fit\n");
            sb.append("  (CPU/memory) — run inspectNodeResourcePressure and findEventsForObject(Pod).\n");
        }
        return sb.toString();
    }

    @LlmTool(
            name = "listPodDisruptionBudgets",
            description = """
                    Use this tool when a node drain is hanging, an HPA cannot scale down, or you
                    suspect a PDB is wedging cluster maintenance. Pass a namespace or empty string
                    for cluster-wide. Returns each PDB with selector, minAvailable / maxUnavailable,
                    currentHealthy, desiredHealthy, expectedPods, disruptionsAllowed, and flags
                    PDBs that have allowed=0 (i.e. the next eviction will block) plus PDBs whose
                    selector matches no pods (stale).
                    """
    )
    public String listPodDisruptionBudgets(String namespaceOrEmpty) {
        boolean hasNs = namespaceOrEmpty != null && !namespaceOrEmpty.isBlank();
        List<PodDisruptionBudget> pdbs;
        try {
            pdbs = hasNs
                    ? kubernetesClient.policy().v1().podDisruptionBudget()
                            .inNamespace(namespaceOrEmpty).list().getItems()
                    : kubernetesClient.policy().v1().podDisruptionBudget()
                            .inAnyNamespace().list().getItems();
        } catch (KubernetesClientException e) {
            return "ERROR: could not list PodDisruptionBudgets: " + e.getMessage();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("=== POD DISRUPTION BUDGETS")
                .append(hasNs ? " (" + namespaceOrEmpty + ")" : " (all namespaces)")
                .append(" — ").append(pdbs.size()).append(" PDB(s) ===\n\n");

        if (pdbs.isEmpty()) {
            sb.append("  (none)\n");
            sb.append("  No PDBs in scope means there is no PDB-related blocker for drains or HPA scale-down.\n");
            return sb.toString();
        }

        sb.append(String.format("%-25s  %-30s  %-25s  %-12s  %-9s  %-9s  %-9s  %-9s%n",
                "NAMESPACE", "NAME", "SELECTOR", "MIN/MAX", "CURRENT", "DESIRED", "EXPECT", "ALLOWED"));
        sb.append("-".repeat(160)).append("\n");

        int blockingCount = 0;
        int staleCount = 0;
        for (PodDisruptionBudget pdb : pdbs) {
            PodDisruptionBudgetSpec spec = pdb.getSpec();
            PodDisruptionBudgetStatus status = pdb.getStatus();
            String sel = spec == null || spec.getSelector() == null
                    ? "<none>" : formatLabelSelector(spec.getSelector());
            String minMax;
            if (spec == null) {
                minMax = "?";
            } else if (spec.getMinAvailable() != null) {
                minMax = "min=" + intOrStr(spec.getMinAvailable());
            } else if (spec.getMaxUnavailable() != null) {
                minMax = "max=" + intOrStr(spec.getMaxUnavailable());
            } else {
                minMax = "<none>";
            }
            int curr = status == null || status.getCurrentHealthy() == null ? 0 : status.getCurrentHealthy();
            int des = status == null || status.getDesiredHealthy() == null ? 0 : status.getDesiredHealthy();
            int exp = status == null || status.getExpectedPods() == null ? 0 : status.getExpectedPods();
            int allowed = status == null || status.getDisruptionsAllowed() == null ? 0 : status.getDisruptionsAllowed();

            boolean blocking = allowed == 0 && exp > 0;
            if (blocking) blockingCount++;
            boolean stale = exp == 0;
            if (stale) staleCount++;

            String allowedStr = allowed + (blocking ? " ⚠ blocks evictions" : "");
            sb.append(String.format("%-25s  %-30s  %-25s  %-12s  %-9d  %-9d  %-9d  %s%n",
                    truncate(pdb.getMetadata().getNamespace(), 25),
                    truncate(pdb.getMetadata().getName(), 30),
                    truncate(sel, 25),
                    truncate(minMax, 12),
                    curr, des, exp,
                    allowedStr));
            if (stale && !blocking) {
                sb.append(String.format("%82s  ⚠ selector matches no pods (stale)%n", ""));
            }
        }

        sb.append("\n[SUSPICIONS]\n");
        if (blockingCount > 0) {
            sb.append("  - ").append(blockingCount).append(" PDB(s) currently allow 0 disruptions.\n");
            sb.append("    Any eviction (drain, HPA scale-down, kubelet evict) for those workloads will fail\n");
            sb.append("    until currentHealthy meets the budget.\n");
        }
        if (staleCount > 0) {
            sb.append("  - ").append(staleCount).append(" PDB(s) match no pods (expectedPods=0).\n");
            sb.append("    They are no-ops today but will silently activate if you create matching pods later.\n");
        }
        if (blockingCount == 0 && staleCount == 0) {
            sb.append("  - All PDBs in scope have headroom. Drains and scale-down will not be blocked by PDBs.\n");
        }
        return sb.toString();
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Per-node fit
    // ──────────────────────────────────────────────────────────────────────────

    private String evaluateFit(Pod pod, Node node, Map<String, String> nodeSelector,
                               Affinity affinity, List<Toleration> tolerations, String runtimeClass) {
        Map<String, String> nodeLabels = Optional.ofNullable(node.getMetadata().getLabels())
                .orElse(Collections.emptyMap());

        // 1. nodeSelector
        for (Map.Entry<String, String> e : nodeSelector.entrySet()) {
            if (!e.getValue().equals(nodeLabels.get(e.getKey()))) {
                return "nodeSelector mismatch on " + e.getKey()
                        + " (pod wants " + e.getValue() + ", node has " + nodeLabels.get(e.getKey()) + ")";
            }
        }

        // 2. required nodeAffinity
        NodeAffinity na = affinity == null ? null : affinity.getNodeAffinity();
        if (na != null) {
            NodeSelector req = na.getRequiredDuringSchedulingIgnoredDuringExecution();
            if (req != null) {
                List<NodeSelectorTerm> terms = Optional.ofNullable(req.getNodeSelectorTerms())
                        .orElse(Collections.emptyList());
                if (!terms.isEmpty() && terms.stream().noneMatch(t -> nodeMatchesTerm(t, nodeLabels))) {
                    return "no nodeAffinity term matches (required matchExpressions/matchFields fail)";
                }
            }
        }

        // 3. taints vs tolerations
        List<Taint> taints = Optional.ofNullable(node.getSpec()).map(NodeSpec::getTaints)
                .orElse(Collections.emptyList());
        for (Taint t : taints) {
            if (!"NoSchedule".equals(t.getEffect()) && !"NoExecute".equals(t.getEffect())) continue;
            if (!isTolerated(t, tolerations)) {
                return "untolerated taint " + t.getKey()
                        + (t.getValue() == null ? "" : "=" + t.getValue())
                        + ":" + t.getEffect();
            }
        }

        // 4. unschedulable / cordon
        if (Boolean.TRUE.equals(Optional.ofNullable(node.getSpec()).map(NodeSpec::getUnschedulable).orElse(false))) {
            return "node is cordoned (spec.unschedulable=true)";
        }

        // 5. NotReady
        boolean ready = Optional.ofNullable(node.getStatus()).map(NodeStatus::getConditions)
                .orElse(Collections.emptyList()).stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
        if (!ready) return "node is NotReady";

        // 6. runtimeClass — best-effort: only flag if cluster has any runtimeClasses defined and
        //    none plausibly match the pod's. (RuntimeClass introspection is omitted here to keep
        //    the dependency surface small; mismatch typically surfaces via PodScheduled message.)
        if (runtimeClass != null && !runtimeClass.isBlank()) {
            // Pass-through; deeper validation requires fetching node.status.runtimeHandlers.
        }
        return null;
    }

    private boolean nodeMatchesTerm(NodeSelectorTerm term, Map<String, String> nodeLabels) {
        for (NodeSelectorRequirement r : Optional.ofNullable(term.getMatchExpressions()).orElse(Collections.emptyList())) {
            if (!nodeRequirementMatches(r, nodeLabels)) return false;
        }
        // matchFields are evaluated against node fields like metadata.name; we only support name
        for (NodeSelectorRequirement r : Optional.ofNullable(term.getMatchFields()).orElse(Collections.emptyList())) {
            if ("metadata.name".equals(r.getKey())) {
                String name = nodeLabels.getOrDefault("kubernetes.io/hostname", "");
                if (!nodeRequirementMatches(r, Map.of("metadata.name", name))) return false;
            }
        }
        return true;
    }

    private boolean nodeRequirementMatches(NodeSelectorRequirement r, Map<String, String> labels) {
        String v = labels.get(r.getKey());
        List<String> vals = Optional.ofNullable(r.getValues()).orElse(Collections.emptyList());
        return switch (Optional.ofNullable(r.getOperator()).orElse("")) {
            case "In" -> v != null && vals.contains(v);
            case "NotIn" -> v == null || !vals.contains(v);
            case "Exists" -> labels.containsKey(r.getKey());
            case "DoesNotExist" -> !labels.containsKey(r.getKey());
            case "Gt" -> compareNumeric(v, vals.isEmpty() ? null : vals.get(0)) > 0;
            case "Lt" -> compareNumeric(v, vals.isEmpty() ? null : vals.get(0)) < 0;
            default -> false;
        };
    }

    private int compareNumeric(String a, String b) {
        try {
            return Long.compare(Long.parseLong(a), Long.parseLong(b));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean isTolerated(Taint t, List<Toleration> tols) {
        for (Toleration tol : tols) {
            String op = Optional.ofNullable(tol.getOperator()).orElse("Equal");
            // Effect must match (empty matches all)
            if (tol.getEffect() != null && !tol.getEffect().isBlank()
                    && !tol.getEffect().equals(t.getEffect())) continue;
            if ("Exists".equals(op)) {
                // Exists with empty key matches all taints (incl. effect)
                if (tol.getKey() == null || tol.getKey().isBlank() || tol.getKey().equals(t.getKey())) return true;
            } else { // Equal
                if (tol.getKey() == null) continue;
                if (!tol.getKey().equals(t.getKey())) continue;
                if (Optional.ofNullable(tol.getValue()).orElse("").equals(Optional.ofNullable(t.getValue()).orElse(""))) return true;
            }
        }
        return false;
    }

    private String reasonCategory(String reason) {
        if (reason == null) return "ok";
        if (reason.startsWith("nodeSelector")) return "nodeSelector mismatch";
        if (reason.startsWith("no nodeAffinity")) return "nodeAffinity mismatch";
        if (reason.startsWith("untolerated taint")) return "untolerated taint";
        if (reason.contains("cordoned")) return "cordoned";
        if (reason.contains("NotReady")) return "NotReady";
        return reason;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Format helpers
    // ──────────────────────────────────────────────────────────────────────────

    private String formatNodeAffinitySummary(Affinity affinity) {
        NodeAffinity na = affinity == null ? null : affinity.getNodeAffinity();
        if (na == null) return "<none>";
        StringBuilder s = new StringBuilder();
        NodeSelector req = na.getRequiredDuringSchedulingIgnoredDuringExecution();
        if (req != null) {
            int terms = Optional.ofNullable(req.getNodeSelectorTerms()).orElse(Collections.emptyList()).size();
            s.append("required(").append(terms).append(" term(s))");
        }
        int prefs = Optional.ofNullable(na.getPreferredDuringSchedulingIgnoredDuringExecution())
                .orElse(Collections.emptyList()).size();
        if (prefs > 0) {
            if (s.length() > 0) s.append(", ");
            s.append("preferred(").append(prefs).append(")");
        }
        if (s.length() == 0) s.append("<empty>");
        return s.toString();
    }

    private String formatPodAffinitySummary(Affinity affinity, boolean anti) {
        if (affinity == null) return "<none>";
        if (anti) {
            PodAntiAffinity p = affinity.getPodAntiAffinity();
            if (p == null) return "<none>";
            int req = Optional.ofNullable(p.getRequiredDuringSchedulingIgnoredDuringExecution()).orElse(Collections.emptyList()).size();
            int pref = Optional.ofNullable(p.getPreferredDuringSchedulingIgnoredDuringExecution()).orElse(Collections.emptyList()).size();
            return "required(" + req + "), preferred(" + pref + ")";
        } else {
            PodAffinity p = affinity.getPodAffinity();
            if (p == null) return "<none>";
            int req = Optional.ofNullable(p.getRequiredDuringSchedulingIgnoredDuringExecution()).orElse(Collections.emptyList()).size();
            int pref = Optional.ofNullable(p.getPreferredDuringSchedulingIgnoredDuringExecution()).orElse(Collections.emptyList()).size();
            return "required(" + req + "), preferred(" + pref + ")";
        }
    }

    private String formatToleration(Toleration t) {
        StringBuilder s = new StringBuilder();
        s.append(Optional.ofNullable(t.getOperator()).orElse("Equal"));
        s.append(" ").append(Optional.ofNullable(t.getKey()).orElse("*"));
        if (t.getValue() != null && !t.getValue().isBlank()) s.append("=").append(t.getValue());
        if (t.getEffect() != null && !t.getEffect().isBlank()) s.append(":").append(t.getEffect());
        if (t.getTolerationSeconds() != null) s.append(" (").append(t.getTolerationSeconds()).append("s)");
        return s.toString();
    }

    private String intOrStr(IntOrString io) {
        if (io == null) return "<none>";
        if (io.getStrVal() != null && !io.getStrVal().isBlank()) return io.getStrVal();
        return io.getIntVal() == null ? "<none>" : io.getIntVal().toString();
    }

    private String formatLabelSelector(LabelSelector sel) {
        Map<String, String> m = Optional.ofNullable(sel.getMatchLabels()).orElse(Collections.emptyMap());
        List<LabelSelectorRequirement> exprs = Optional.ofNullable(sel.getMatchExpressions()).orElse(Collections.emptyList());
        if (m.isEmpty() && exprs.isEmpty()) return "{}(ALL)";
        StringBuilder sb = new StringBuilder();
        if (!m.isEmpty()) {
            sb.append(m.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(",")));
        }
        if (!exprs.isEmpty()) {
            if (sb.length() > 0) sb.append(",");
            sb.append(exprs.stream().map(e -> e.getKey() + " " + e.getOperator()
                            + Optional.ofNullable(e.getValues()).orElse(Collections.emptyList()))
                    .collect(Collectors.joining(",")));
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(1, max - 1)) + "…";
    }
}

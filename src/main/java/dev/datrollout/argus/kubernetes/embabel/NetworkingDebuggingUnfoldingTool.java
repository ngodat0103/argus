package dev.datrollout.argus.kubernetes.embabel;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.progressive.UnfoldingTool;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class NetworkingDebuggingUnfoldingTool implements UnfoldingTool {
    private final NetworkingDebuggingTools networkingDebuggingTools;

    @Override
    public @NotNull List<Tool> getInnerTools() {
        return Tool.fromInstance(networkingDebuggingTools);
    }

    @Override
    public @NotNull Result call(@NotNull String input) {
        return Result.text(
                "Networking diagnostic tools are now available.\n" +
                "DISCOVERY FIRST — when you don't know exact names, call findServices / findPods / " +
                "listServices / listNamespaces before any inspect* tool. Do NOT guess names.\n" +
                "Use the inspect* tools to investigate unreachable Services, empty or NotReady " +
                "Endpoints, Ingress routing and TLS errors, NetworkPolicy isolation, pod-level " +
                "networking issues (missing IP, probe failures, hostNetwork side effects), " +
                "CoreDNS / 'no such host' problems, and cluster-wide CNI / kube-proxy / " +
                "LoadBalancer state."
        );
    }

    @Override
    public @NotNull Definition getDefinition() {
        String name = "kubernetes-networking-diagnostics";
        String description = """
                Entry point for Kubernetes networking troubleshooting tools. Invoke this when the \
                operator reports any networking symptom, including: a Service that is unreachable, \
                returns 'connection refused', 'no route to host', or times out; Endpoints that are \
                empty, NotReady, or terminating; an Ingress that returns 404 / default-backend / a \
                TLS error / has a pending external address; suspected NetworkPolicy blocking \
                ingress or egress traffic; pod-level network problems such as missing pod IP, \
                hostNetwork side effects, wrong dnsPolicy, or readiness/liveness probe failures; \
                CoreDNS / kube-dns failures producing 'no such host'; and cluster-level questions \
                about which CNI, kube-proxy, or LoadBalancer provider is installed. \
                Also exposes discovery tools (findServices, findPods, listServices, \
                listNamespaces) — call those FIRST whenever the exact namespace or resource name \
                is not already known, instead of guessing names into the inspect* tools. \
                Exposes: listNamespaces, listServices, findServices, findPods, \
                inspectServiceConnectivity, inspectEndpoints, inspectIngressRouting, \
                listIngresses, inspectIngressControllers, inspectNetworkPoliciesForPod, \
                listNetworkPolicies, inspectPodNetworking, inspectDNSHealth, \
                inspectClusterNetworkInfrastructure.\
                """;
        return Definition.create(name, description, InputSchema.empty());
    }
}

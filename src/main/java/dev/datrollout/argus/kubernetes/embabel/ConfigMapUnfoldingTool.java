package dev.datrollout.argus.kubernetes.embabel;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.progressive.UnfoldingTool;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConfigMapUnfoldingTool implements UnfoldingTool {
    private final ConfigMapTool configMapTool;

    @Override
    public @NotNull List<Tool> getInnerTools() {
        return Tool.fromInstance(configMapTool);
    }

    @Override
    public @NotNull Result call(@NotNull String input) {
        return Result.text("ConfigMap inspection tools are now available. Use listConfigMaps to discover "
                + "namespaced ConfigMaps and their keys without dumping values, then use readConfigMap "
                + "for bounded, LLM-friendly summaries of one ConfigMap or one specific key. These tools "
                + "never return raw full YAML/JSON manifests, unbounded long text, or binaryData contents.");
    }

    @Override
    public @NotNull Definition getDefinition() {
        String name = "kubernetes-configmap-inspection";
        String description = """
                Entry point for reading Kubernetes ConfigMaps safely for an LLM. Invoke this when \
                the operator asks to inspect application configuration, troubleshoot a workload whose \
                pods are gone or scaled to zero, compare config keys, or investigate namespaces like \
                crowdsec where configuration may remain after pods were deleted. Output is bounded \
                and summary-first: no raw full YAML/JSON, no long text dumps, and no binaryData dump. \
                Exposes: listConfigMaps, readConfigMap.\
                """;
        return Definition.create(name, description, InputSchema.empty());
    }
}

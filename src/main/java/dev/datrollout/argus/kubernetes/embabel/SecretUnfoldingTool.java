package dev.datrollout.argus.kubernetes.embabel;

import com.embabel.agent.api.tool.Tool;
import com.embabel.agent.api.tool.progressive.UnfoldingTool;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SecretUnfoldingTool implements UnfoldingTool {
    private final SecretTool secretTool;

    @Override
    public @NotNull List<Tool> getInnerTools() {
        return Tool.fromInstance(secretTool);
    }

    @Override
    public @NotNull Result call(@NotNull String input) {
        return Result.text("Secret inspection tools are now available. Use listSecrets to discover "
                + "namespaced Secrets and their key names without any value content. Use readSecret "
                + "for a single Secret or single key with bounded, partially-masked output: decoded "
                + "byte length, sha256 short fingerprint, and an edges-safe preview (first 2 + '***' "
                + "+ last 2) ONLY when the decoded value is at least 12 printable UTF-8 bytes. All "
                + "other values are returned as <masked>. Raw plaintext, raw base64, and binary "
                + "content are never returned. Do not claim to have read a secret value.");
    }

    @Override
    public @NotNull Definition getDefinition() {
        String name = "kubernetes-secret-inspection";
        String description = """
                Entry point for reading Kubernetes Secrets safely for an LLM. Invoke this when \
                the operator wants to verify which credential keys exist, confirm a key is \
                present, compare key sets across namespaces, or fingerprint a value to check \
                whether two Secrets hold the same payload. Output is bounded and partially \
                masked at all times: keys are visible, values are NOT. Returns decoded byte \
                length, sha256 short fingerprint, and an edges-safe masked preview (first 2 + \
                '***' + last 2) only when the decoded value is at least 12 printable UTF-8 \
                bytes; all other values are returned as <masked>. Never returns raw plaintext, \
                raw base64, or binary content. Exposes: listSecrets, readSecret.\
                """;
        return Definition.create(name, description, InputSchema.empty());
    }
}

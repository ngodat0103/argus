package dev.datrollout.argus.kubernetes.embabel;

import com.embabel.agent.api.annotation.LlmTool;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LLM-callable tools for inspecting Kubernetes Secrets safely.
 *
 * <p>Hard contract: this tool NEVER returns raw decoded Secret values to the LLM. Output is limited
 * to key names, byte length, sha256 fingerprint, and an "edges-safe" masked preview (first 2 +
 * "***" + last 2) for printable values whose decoded length is at least 12 bytes. All other values
 * are returned as {@code <masked>}.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class SecretTool {

    private static final int MAX_SECRETS = 80;
    private static final int MAX_KEY_NAMES = 14;
    private static final int MAX_LABELS = 8;
    private static final int MIN_REVEAL_LENGTH = 12;
    private static final int FINGERPRINT_HEX_CHARS = 8;
    private static final String MASKED = "<masked>";

    private final KubernetesClient kubernetesClient;

    @LlmTool(name = "listSecrets", description = """
                    Use this tool to discover Secrets in a namespace. Returns ONLY metadata: name,
                    type, creation timestamp, labels (capped), and the names of keys present in
                    data and stringData. NEVER returns secret values, base64 blobs, fingerprints,
                    or previews. Output is capped for LLM context safety. Follow up with
                    readSecret(namespace, name, key) to inspect a single key with bounded,
                    partially-masked output.
                    """)
    public String listSecrets(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return "ERROR: namespace is required.";
        }

        List<Secret> secrets;
        try {
            secrets = kubernetesClient.secrets().inNamespace(namespace).list().getItems();
        } catch (KubernetesClientException e) {
            return "ERROR: could not list Secrets in namespace " + namespace + ": " + e.getMessage();
        }

        secrets = secrets.stream()
                .sorted(Comparator.comparing(s -> safe(s.getMetadata().getName())))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("=== SECRETS: ").append(namespace).append(" ===\n\n");
        sb.append("count: ").append(secrets.size()).append("\n");
        if (secrets.size() > MAX_SECRETS) {
            sb.append("showing: first ").append(MAX_SECRETS).append(" alphabetically\n");
        }
        sb.append(
                "values: never returned by listSecrets; call readSecret(namespace, name, key) for masked summaries.\n\n");

        if (secrets.isEmpty()) {
            sb.append("(none)\n");
            return sb.toString();
        }

        for (Secret secret : secrets.stream().limit(MAX_SECRETS).toList()) {
            Set<String> allKeys = combinedKeys(secret);
            sb.append("- ").append(secret.getMetadata().getName()).append("\n");
            sb.append("  type: ").append(safe(secret.getType())).append("\n");
            sb.append("  created: ")
                    .append(safe(secret.getMetadata().getCreationTimestamp()))
                    .append("\n");
            sb.append("  labels: ")
                    .append(formatMap(secret.getMetadata().getLabels(), MAX_LABELS))
                    .append("\n");
            sb.append("  keys: ")
                    .append(allKeys.size())
                    .append(" ")
                    .append(formatKeys(allKeys, MAX_KEY_NAMES))
                    .append("\n");
        }

        sb.append(
                "\nNext step: call readSecret(namespace, name, key) for one key. Values are always partially masked.\n");
        return sb.toString();
    }

    @LlmTool(name = "readSecret", description = """
                    Use this tool to inspect Secret keys with bounded, partially-masked output.
                    Pass namespace and Secret name. If key is blank, returns a per-key summary
                    for all keys; otherwise returns only the requested key. For each key returns:
                    decoded byte length, sha256 short fingerprint, and an "edges-safe" masked
                    preview (first 2 + "***" + last 2) ONLY when decoded length >= 12 and the
                    value is printable UTF-8. All other values are returned as <masked>. Never
                    returns raw plaintext, raw base64, or binary content under any path.
                    """)
    public String readSecret(String namespace, String name, String key) {
        if (namespace == null || namespace.isBlank() || name == null || name.isBlank()) {
            return "ERROR: namespace and name are required.";
        }

        Secret secret;
        try {
            secret = kubernetesClient
                    .secrets()
                    .inNamespace(namespace)
                    .withName(name)
                    .get();
        } catch (KubernetesClientException e) {
            return "ERROR: could not read Secret " + namespace + "/" + name + ": " + e.getMessage();
        }

        if (secret == null) {
            return "ERROR: Secret " + namespace + "/" + name + " not found.";
        }

        Map<String, String> data = Optional.ofNullable(secret.getData()).orElse(Collections.emptyMap());
        Map<String, String> stringData =
                Optional.ofNullable(secret.getStringData()).orElse(Collections.emptyMap());
        Set<String> allKeys = combinedKeys(secret);
        String wantedKey = key == null ? "" : key.trim();

        StringBuilder sb = new StringBuilder();
        sb.append("=== SECRET SUMMARY: ")
                .append(namespace)
                .append("/")
                .append(name)
                .append(" ===\n\n");
        sb.append("type: ").append(safe(secret.getType())).append("\n");
        sb.append("created: ")
                .append(safe(secret.getMetadata().getCreationTimestamp()))
                .append("\n");
        sb.append("labels: ")
                .append(formatMap(secret.getMetadata().getLabels(), MAX_LABELS))
                .append("\n");
        sb.append("keys: ")
                .append(allKeys.size())
                .append(" ")
                .append(formatKeys(allKeys, MAX_KEY_NAMES))
                .append("\n");
        sb.append("format: edges-safe masked previews only; raw values are never returned.\n");

        if (!wantedKey.isBlank()) {
            sb.append("\n[Selected key: ").append(wantedKey).append("]\n");
            if (stringData.containsKey(wantedKey)) {
                appendKeySummary(sb, wantedKey, plainBytes(stringData.get(wantedKey)), "stringData");
            } else if (data.containsKey(wantedKey)) {
                appendKeySummary(sb, wantedKey, decodeBase64(data.get(wantedKey)), "data");
            } else {
                sb.append("  ERROR: key not found.\n");
                sb.append("  availableKeys: ")
                        .append(formatKeys(allKeys, MAX_KEY_NAMES))
                        .append("\n");
            }
            return sb.toString();
        }

        sb.append("\n[Key summaries]\n");
        if (allKeys.isEmpty()) {
            sb.append("  (none)\n");
            return sb.toString();
        }

        for (String secretKey : allKeys) {
            if (stringData.containsKey(secretKey)) {
                appendKeySummary(sb, secretKey, plainBytes(stringData.get(secretKey)), "stringData");
            } else {
                appendKeySummary(sb, secretKey, decodeBase64(data.get(secretKey)), "data");
            }
        }

        sb.append("\nNext step: call readSecret(namespace, name, key) for one key when you need a focused view.\n");
        return sb.toString();
    }

    private void appendKeySummary(StringBuilder sb, String key, byte[] decoded, String source) {
        int length = decoded == null ? 0 : decoded.length;
        sb.append("- ").append(key).append("\n");
        sb.append("  source: ").append(source).append("\n");
        sb.append("  decodedBytes: ").append(length).append("\n");
        sb.append("  sha256: ").append(shortFingerprint(decoded)).append("\n");
        sb.append("  preview: ").append(maskedPreview(decoded)).append("\n");
    }

    /**
     * Edges-safe masked preview. Reveals only first 2 and last 2 characters, and only when the
     * decoded value is at least 12 bytes long and is printable UTF-8 text. All other shapes return
     * {@code <masked>} so binary blobs, certs, keys, and short tokens are never partially leaked.
     */
    private String maskedPreview(byte[] decoded) {
        if (decoded == null || decoded.length == 0) return "<empty>";
        if (decoded.length < MIN_REVEAL_LENGTH) return MASKED;
        if (!isPrintableUtf8(decoded)) return MASKED;
        String text = new String(decoded, StandardCharsets.UTF_8);
        if (text.length() < MIN_REVEAL_LENGTH) return MASKED;
        return text.substring(0, 2) + "***" + text.substring(text.length() - 2);
    }

    private String shortFingerprint(byte[] decoded) {
        if (decoded == null || decoded.length == 0) return "<empty>";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(decoded);
            String hex = HexFormat.of().formatHex(hash);
            return hex.substring(0, FINGERPRINT_HEX_CHARS);
        } catch (NoSuchAlgorithmException e) {
            return "<unavailable>";
        }
    }

    private boolean isPrintableUtf8(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return false;
        String text;
        try {
            text = new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return false;
        }
        if (text.getBytes(StandardCharsets.UTF_8).length != bytes.length) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r') continue;
            if (c < 0x20 || c == 0x7F) return false;
        }
        return true;
    }

    private byte[] decodeBase64(String encoded) {
        if (encoded == null || encoded.isEmpty()) return new byte[0];
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }

    private byte[] plainBytes(String value) {
        if (value == null) return new byte[0];
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private Set<String> combinedKeys(Secret secret) {
        Set<String> keys = new TreeSet<>();
        Optional.ofNullable(secret.getData()).ifPresent(m -> keys.addAll(m.keySet()));
        Optional.ofNullable(secret.getStringData()).ifPresent(m -> keys.addAll(m.keySet()));
        return keys;
    }

    private String formatMap(Map<String, String> values, int maxEntries) {
        if (values == null || values.isEmpty()) return "<none>";
        return values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(maxEntries)
                .map(e -> e.getKey() + "=" + truncate(e.getValue(), 40))
                .reduce((a, b) -> a + ", " + b)
                .map(s -> values.size() > maxEntries ? s + ", ... +" + (values.size() - maxEntries) : s)
                .orElse("<none>");
    }

    private String formatKeys(Set<String> keys, int maxKeys) {
        if (keys == null || keys.isEmpty()) return "[]";
        Set<String> sorted = new LinkedHashSet<>(keys.stream().sorted().toList());
        String joined = sorted.stream()
                .limit(maxKeys)
                .map(k -> truncate(k, 60))
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        if (sorted.size() > maxKeys) joined += ", ... +" + (sorted.size() - maxKeys);
        return "[" + joined + "]";
    }

    private String safe(String s) {
        return s == null || s.isBlank() ? "<none>" : s;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, Math.max(1, max - 3)) + "...";
    }
}

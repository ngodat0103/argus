package dev.datrollout.argus.kubernetes.embabel;

import com.embabel.agent.api.annotation.LlmTool;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@RequiredArgsConstructor
@Component
public class ConfigMapTool {

    private static final int MAX_CONFIGMAPS = 80;
    private static final int MAX_KEY_NAMES = 14;
    private static final int MAX_LABELS = 8;
    private static final int MAX_PREVIEW_LINES = 6;
    private static final int MAX_PREVIEW_CHARS = 320;
    private static final int MAX_STRUCTURE_KEYS = 12;

    private final KubernetesClient kubernetesClient;

    @LlmTool(name = "listConfigMaps", description = """
                    Use this tool when you need to discover ConfigMaps in a namespace before
                    reading configuration. Returns an LLM-friendly index only: ConfigMap name,
                    creation timestamp, labels, data/binaryData key counts, key names, and short
                    hints for likely relevant configuration. Does NOT dump ConfigMap values,
                    YAML, or JSON blobs. Output is capped for LLM context safety.
                    """)
    public String listConfigMaps(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            return "ERROR: namespace is required.";
        }

        List<ConfigMap> configMaps;
        try {
            configMaps =
                    kubernetesClient.configMaps().inNamespace(namespace).list().getItems();
        } catch (KubernetesClientException e) {
            return "ERROR: could not list ConfigMaps in namespace " + namespace + ": " + e.getMessage();
        }

        configMaps = configMaps.stream()
                .sorted(Comparator.comparing(cm -> safe(cm.getMetadata().getName())))
                .toList();

        StringBuilder sb = new StringBuilder();
        sb.append("=== CONFIGMAPS: ").append(namespace).append(" ===\n\n");
        sb.append("count: ").append(configMaps.size()).append("\n");
        if (configMaps.size() > MAX_CONFIGMAPS) {
            sb.append("showing: first ").append(MAX_CONFIGMAPS).append(" alphabetically\n");
        }
        sb.append("values: omitted by design; use readConfigMap(namespace, name, key) for summaries.\n\n");

        if (configMaps.isEmpty()) {
            sb.append("(none)\n");
            return sb.toString();
        }

        for (ConfigMap cm : configMaps.stream().limit(MAX_CONFIGMAPS).toList()) {
            Map<String, String> data = Optional.ofNullable(cm.getData()).orElse(Collections.emptyMap());
            Map<String, String> binaryData =
                    Optional.ofNullable(cm.getBinaryData()).orElse(Collections.emptyMap());

            sb.append("- ").append(cm.getMetadata().getName()).append("\n");
            sb.append("  created: ")
                    .append(safe(cm.getMetadata().getCreationTimestamp()))
                    .append("\n");
            sb.append("  labels: ")
                    .append(formatMap(cm.getMetadata().getLabels(), MAX_LABELS))
                    .append("\n");
            sb.append("  dataKeys: ")
                    .append(data.size())
                    .append(" ")
                    .append(formatKeys(data.keySet(), MAX_KEY_NAMES))
                    .append("\n");
            sb.append("  binaryDataKeys: ")
                    .append(binaryData.size())
                    .append(" ")
                    .append(formatKeys(binaryData.keySet(), MAX_KEY_NAMES))
                    .append("\n");
            String hint = configHint(data.keySet());
            if (!hint.isBlank()) sb.append("  hint: ").append(hint).append("\n");
        }

        sb.append("\nNext step: call readConfigMap(namespace, name, key) with a specific key when possible.\n");
        return sb.toString();
    }

    @LlmTool(name = "readConfigMap", description = """
                    Use this tool to inspect a ConfigMap in an LLM-friendly way. Pass namespace and
                    ConfigMap name. If key is blank, returns a bounded per-key summary: type hint,
                    line/character counts, top-level JSON/YAML-looking keys where practical, and a
                    short preview. If key is provided, returns only that key with the same bounded
                    summary. Never returns raw full YAML, JSON, long text, or binary data.
                    """)
    public String readConfigMap(String namespace, String name, String key) {
        if (namespace == null || namespace.isBlank() || name == null || name.isBlank()) {
            return "ERROR: namespace and name are required.";
        }

        ConfigMap configMap;
        try {
            configMap = kubernetesClient
                    .configMaps()
                    .inNamespace(namespace)
                    .withName(name)
                    .get();
        } catch (KubernetesClientException e) {
            return "ERROR: could not read ConfigMap " + namespace + "/" + name + ": " + e.getMessage();
        }

        if (configMap == null) {
            return "ERROR: ConfigMap " + namespace + "/" + name + " not found.";
        }

        Map<String, String> data = Optional.ofNullable(configMap.getData()).orElse(Collections.emptyMap());
        Map<String, String> binaryData =
                Optional.ofNullable(configMap.getBinaryData()).orElse(Collections.emptyMap());
        String wantedKey = key == null ? "" : key.trim();

        StringBuilder sb = new StringBuilder();
        sb.append("=== CONFIGMAP SUMMARY: ")
                .append(namespace)
                .append("/")
                .append(name)
                .append(" ===\n\n");
        sb.append("created: ")
                .append(safe(configMap.getMetadata().getCreationTimestamp()))
                .append("\n");
        sb.append("labels: ")
                .append(formatMap(configMap.getMetadata().getLabels(), MAX_LABELS))
                .append("\n");
        sb.append("dataKeys: ")
                .append(data.size())
                .append(" ")
                .append(formatKeys(data.keySet(), MAX_KEY_NAMES))
                .append("\n");
        sb.append("binaryDataKeys: ")
                .append(binaryData.size())
                .append(" ")
                .append(formatKeys(binaryData.keySet(), MAX_KEY_NAMES))
                .append("\n");
        sb.append("format: bounded summaries only; no raw ConfigMap YAML/JSON or long values.\n");

        if (!wantedKey.isBlank()) {
            sb.append("\n[Selected key: ").append(wantedKey).append("]\n");
            if (data.containsKey(wantedKey)) {
                appendValueSummary(sb, wantedKey, data.get(wantedKey));
            } else if (binaryData.containsKey(wantedKey)) {
                appendBinarySummary(sb, wantedKey, binaryData.get(wantedKey));
            } else {
                sb.append("  ERROR: key not found.\n");
                sb.append("  availableDataKeys: ")
                        .append(formatKeys(data.keySet(), MAX_KEY_NAMES))
                        .append("\n");
                sb.append("  availableBinaryDataKeys: ")
                        .append(formatKeys(binaryData.keySet(), MAX_KEY_NAMES))
                        .append("\n");
            }
            return sb.toString();
        }

        sb.append("\n[Data key summaries]\n");
        if (data.isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (String dataKey : data.keySet().stream().sorted().toList()) {
                appendValueSummary(sb, dataKey, data.get(dataKey));
            }
        }

        if (!binaryData.isEmpty()) {
            sb.append("\n[Binary data summaries]\n");
            for (String binaryKey : binaryData.keySet().stream().sorted().toList()) {
                appendBinarySummary(sb, binaryKey, binaryData.get(binaryKey));
            }
        }

        sb.append("\nNext step: call readConfigMap(namespace, name, key) for one key when you need more focus.\n");
        return sb.toString();
    }

    private void appendValueSummary(StringBuilder sb, String key, String value) {
        String body = value == null ? "" : value;
        sb.append("- ").append(key).append("\n");
        sb.append("  type: ").append(typeHint(key, body)).append("\n");
        sb.append("  chars: ").append(body.length()).append("\n");
        sb.append("  lines: ").append(lineCount(body)).append("\n");

        List<String> structure = structureHints(body);
        if (!structure.isEmpty()) {
            sb.append("  structure: ").append(String.join(", ", structure)).append("\n");
        }

        String preview = preview(body);
        if (preview.isBlank()) {
            sb.append("  preview: <empty>\n");
        } else {
            sb.append("  preview:\n");
            for (String line : preview.split("\n", -1)) {
                sb.append("    ").append(line).append("\n");
            }
            if (isTruncated(body, preview)) {
                sb.append("  truncated: yes; full value omitted for LLM context safety.\n");
            }
        }
    }

    private void appendBinarySummary(StringBuilder sb, String key, String encoded) {
        sb.append("- ").append(key).append("\n");
        sb.append("  type: binaryData\n");
        sb.append("  encodedChars: ")
                .append(encoded == null ? 0 : encoded.length())
                .append("\n");
        sb.append("  decodedBytes: ").append(decodedBytes(encoded)).append("\n");
        sb.append("  value: omitted because binaryData is not LLM-friendly text.\n");
    }

    private String typeHint(String key, String value) {
        String lowerKey = key == null ? "" : key.toLowerCase();
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return "json-like text";
        if (lowerKey.endsWith(".yaml") || lowerKey.endsWith(".yml") || looksLikeYaml(trimmed)) return "yaml-like text";
        if (lowerKey.endsWith(".conf") || lowerKey.endsWith(".ini") || lowerKey.endsWith(".properties")) {
            return "key/value config text";
        }
        if (lineCount(value) > 1) return "multi-line text";
        return "single-line text";
    }

    private List<String> structureHints(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isBlank()) return Collections.emptyList();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return jsonLikeKeys(trimmed);
        if (looksLikeYaml(trimmed)) return yamlLikeKeys(trimmed);
        return keyValueKeys(trimmed);
    }

    private List<String> jsonLikeKeys(String value) {
        Set<String> keys = new LinkedHashSet<>();
        int depth = 0;
        boolean inString = false;
        boolean escaping = false;
        StringBuilder currentString = new StringBuilder();
        String lastString = null;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (inString) {
                if (escaping) {
                    escaping = false;
                    currentString.append(c);
                } else if (c == '\\') {
                    escaping = true;
                } else if (c == '"') {
                    inString = false;
                    lastString = currentString.toString();
                    currentString.setLength(0);
                } else {
                    currentString.append(c);
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth = Math.max(0, depth - 1);
            } else if (c == ':' && depth <= 2 && lastString != null) {
                keys.add(lastString);
                lastString = null;
                if (keys.size() >= MAX_STRUCTURE_KEYS) break;
            } else if (!Character.isWhitespace(c)) {
                lastString = null;
            }
        }
        return keys.stream().map(k -> "jsonKey=" + truncate(k, 48)).toList();
    }

    private List<String> yamlLikeKeys(String value) {
        Set<String> keys = new LinkedHashSet<>();
        for (String line : value.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("-")) continue;
            int idx = trimmed.indexOf(':');
            if (idx <= 0) continue;
            String candidate = trimmed.substring(0, idx).trim();
            if (candidate.contains(" ") || candidate.length() > 80) continue;
            keys.add(candidate);
            if (keys.size() >= MAX_STRUCTURE_KEYS) break;
        }
        return keys.stream().map(k -> "yamlKey=" + truncate(k, 48)).toList();
    }

    private List<String> keyValueKeys(String value) {
        Set<String> keys = new LinkedHashSet<>();
        for (String line : value.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue;
            int idx = trimmed.indexOf('=');
            if (idx <= 0) continue;
            keys.add(trimmed.substring(0, idx).trim());
            if (keys.size() >= MAX_STRUCTURE_KEYS) break;
        }
        return keys.stream().map(k -> "key=" + truncate(k, 48)).toList();
    }

    private boolean looksLikeYaml(String value) {
        if (value == null || value.isBlank()) return false;
        int yamlLines = 0;
        for (String line : value.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isBlank() || trimmed.startsWith("#")) continue;
            int idx = trimmed.indexOf(':');
            if (idx > 0 && idx < 80) yamlLines++;
            if (yamlLines >= 2) return true;
        }
        return false;
    }

    private String preview(String value) {
        if (value == null || value.isBlank()) return "";
        List<String> lines = new ArrayList<>();
        int chars = 0;
        for (String rawLine : value.split("\n", -1)) {
            if (lines.size() >= MAX_PREVIEW_LINES || chars >= MAX_PREVIEW_CHARS) break;
            String line = rawLine.stripTrailing();
            int remaining = MAX_PREVIEW_CHARS - chars;
            if (line.length() > remaining) {
                line = truncate(line, Math.max(1, remaining));
            }
            lines.add(line);
            chars += line.length() + 1;
        }
        return String.join("\n", lines);
    }

    private boolean isTruncated(String original, String renderedPreview) {
        if (original == null) return false;
        return original.length() > renderedPreview.length() || lineCount(original) > lineCount(renderedPreview);
    }

    private int lineCount(String value) {
        if (value == null || value.isEmpty()) return 0;
        return value.split("\n", -1).length;
    }

    private int decodedBytes(String encoded) {
        if (encoded == null || encoded.isBlank()) return 0;
        try {
            return Base64.getDecoder().decode(encoded).length;
        } catch (IllegalArgumentException e) {
            return encoded.getBytes(StandardCharsets.UTF_8).length;
        }
    }

    private String configHint(Set<String> keys) {
        List<String> matches =
                keys.stream().filter(this::looksImportantKey).sorted().limit(5).toList();
        if (matches.isEmpty()) return "";
        return "likely config keys: " + String.join(", ", matches);
    }

    private boolean looksImportantKey(String key) {
        String k = key == null ? "" : key.toLowerCase();
        return k.contains("config")
                || k.contains("conf")
                || k.contains("yaml")
                || k.contains("yml")
                || k.contains("json")
                || k.contains("ini")
                || k.contains("properties")
                || k.contains("settings")
                || k.contains("crowdsec")
                || k.contains("appsec")
                || k.contains("acquis");
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
        List<String> sorted = keys.stream().sorted().toList();
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

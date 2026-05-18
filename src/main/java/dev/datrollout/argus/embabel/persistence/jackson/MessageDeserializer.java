package dev.datrollout.argus.embabel.persistence.jackson;

import com.embabel.chat.*;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import dev.datrollout.argus.embabel.persistence.EnhancedAssistantMessage;
import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom Jackson deserializer for the polymorphic {@link Message} interface.
 * Dispatches to the appropriate concrete type based on the {@code role} field.
 * <p>
 * ASSISTANT messages are always deserialized as {@link EnhancedAssistantMessage}
 * so that Discord-specific and token-tracking fields are hydrated.
 */
public class MessageDeserializer extends StdDeserializer<Message> {

    public MessageDeserializer() {
        super(Message.class);
    }

    @Override
    public Message deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        ObjectMapper mapper = (ObjectMapper) p.getCodec();
        JsonNode node = mapper.readTree(p);

        String role = node.has("role") ? node.get("role").asText("").toUpperCase() : null;
        if (role == null || role.isBlank()) {
            throw new JsonParseException(p, "Missing 'role' field in message JSON: " + node);
        }

        return switch (role) {
            case "USER" -> deserializeUserMessage(node, mapper, p);
            case "ASSISTANT" -> deserializeEnhancedAssistantMessage(node, mapper, p);
            case "SYSTEM" -> deserializeSystemMessage(node, mapper, p);
            default -> throw new JsonParseException(p, "Unknown message role: " + role);
        };
    }

    private UserMessage deserializeUserMessage(JsonNode node, ObjectMapper mapper, JsonParser p) throws IOException {
        List<ContentPart> parts = new ArrayList<>();
        if (node.has("parts") && node.get("parts").isArray()) {
            for (JsonNode partNode : node.get("parts")) {
                ContentPart part = mapper.treeToValue(partNode, ContentPart.class);
                if (part != null) {
                    parts.add(part);
                }
            }
        }
        // Fallback: legacy text-only message with no parts array
        if (parts.isEmpty() && node.has("content")) {
            parts.add(new TextPart(node.get("content").asText()));
        }
        String name = node.has("name") && !node.get("name").isNull()
                ? node.get("name").asText()
                : null;
        Instant timestamp = readInstant(node, "timestamp");
        return new UserMessage(parts, name, timestamp);
    }

    private EnhancedAssistantMessage deserializeEnhancedAssistantMessage(
            JsonNode node, ObjectMapper mapper, JsonParser p) throws IOException {
        String content = node.has("content") ? node.get("content").asText("") : "";
        String name = node.has("name") && !node.get("name").isNull()
                ? node.get("name").asText()
                : null;
        Instant timestamp = readInstant(node, "timestamp");

        EnhancedAssistantMessage message = new EnhancedAssistantMessage(content, name, timestamp);
        message.setSent(node.has("isSent") && node.get("isSent").asBoolean(false));

        if (node.has("sentAt") && !node.get("sentAt").isNull()) {
            message.setSentAt(OffsetDateTime.parse(node.get("sentAt").asText()));
        }
        if (node.has("reaction") && !node.get("reaction").isNull()) {
            String reaction = node.get("reaction").asText();
            if (!reaction.isBlank()) {
                message.setReaction(reaction.trim());
            }
        }
        if (node.has("reactionUpdatedAt") && !node.get("reactionUpdatedAt").isNull()) {
            message.setReactionUpdatedAt(readInstant(node, "reactionUpdatedAt"));
        }
        if (node.has("parentDiscordMessageId")
                && !node.get("parentDiscordMessageId").isNull()) {
            String parentId = node.get("parentDiscordMessageId").asText();
            if (!parentId.isBlank()) {
                message.setParentDiscordMessageId(parentId);
            }
        }
        message.setPromptTokens(
                node.has("promptTokens") && !node.get("promptTokens").isNull()
                        ? node.get("promptTokens").intValue()
                        : null);
        message.setCompletionTokens(
                node.has("completionTokens") && !node.get("completionTokens").isNull()
                        ? node.get("completionTokens").intValue()
                        : null);
        message.setTotalTokensSpent(
                node.has("totalTokensSpent") && !node.get("totalTokensSpent").isNull()
                        ? node.get("totalTokensSpent").intValue()
                        : null);
        return message;
    }

    private SystemMessage deserializeSystemMessage(JsonNode node, ObjectMapper mapper, JsonParser p) {
        String content = node.has("content") ? node.get("content").asText("") : "";
        Instant timestamp = readInstant(node, "timestamp");
        return new SystemMessage(content, timestamp);
    }

    private Instant readInstant(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull()) {
            return Instant.now();
        }
        JsonNode fieldNode = node.get(field);
        if (fieldNode.isNumber()) {
            return Instant.ofEpochMilli(fieldNode.longValue());
        }
        try {
            return Instant.parse(fieldNode.asText());
        } catch (Exception e) {
            return Instant.now();
        }
    }
}

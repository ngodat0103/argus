package dev.datrollout.argus.embabel.persistence;

import com.embabel.agent.api.reference.LlmReference;
import com.embabel.agent.core.hitl.Awaitable;
import com.embabel.chat.Asset;
import com.embabel.chat.AssistantMessage;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

public class EnhancedAssistantMessage extends AssistantMessage {

    @Getter
    @Setter
    @JsonProperty("isSent")
    private boolean isSent = false;

    @Getter
    @Setter
    private OffsetDateTime sentAt;

    @Getter
    @Setter
    private String reaction;

    @Getter
    @Setter
    private Instant reactionUpdatedAt;

    @Getter
    @Setter
    private String parentDiscordMessageId;

    @Getter
    @Setter
    // Per-message token fields mirrored after send via TokenUsageCaptureListener.
    // Values represent the turn delta (not full conversation totals).
    private Integer promptTokens;

    @Getter
    @Setter
    private Integer completionTokens;

    @Getter
    @Setter
    // Invariant target: totalTokensSpent == promptTokens + completionTokens.
    private Integer totalTokensSpent;

    /**
     * Jackson deserialization creator — restores from JSONB.
     * Awaitable and assets are not persisted (no HITL in argus; no concrete asset types yet).
     */
    @JsonCreator
    public EnhancedAssistantMessage(
            @JsonProperty("content") @NotNull String content,
            @JsonProperty("name") @Nullable String name,
            @JsonProperty("timestamp") @Nullable Instant timestamp) {
        super(content, name, null, List.of(), timestamp != null ? timestamp : Instant.now());
    }

    public EnhancedAssistantMessage(@NotNull String content, @Nullable String name,
            @Nullable Awaitable<?, ?> awaitable, @NotNull List<? extends Asset> assets,
            @NotNull Instant timestamp) {
        super(content, name, awaitable, assets, timestamp);
    }

    public EnhancedAssistantMessage(@NotNull String content, @Nullable String name,
            @Nullable Awaitable<?, ?> awaitable, @NotNull List<? extends Asset> assets) {
        super(content, name, awaitable, assets);
    }

    public EnhancedAssistantMessage(@NotNull String content, @Nullable String name,
            @Nullable Awaitable<?, ?> awaitable) {
        super(content, name, awaitable);
    }

    public EnhancedAssistantMessage(@NotNull String content, @Nullable String name) {
        super(content, name);
    }

    public EnhancedAssistantMessage(@NotNull String content) {
        super(content);
    }

    // getAwaitable() is suppressed via AssistantMessageMixin in MessageJacksonModule
    // (it is final in AssistantMessage so cannot be overridden here).

    @Override
    public @NotNull List<LlmReference> references() {
        return getAssets().stream()
                .map(Asset::reference)
                .toList();
    }
}

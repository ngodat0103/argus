package dev.datrollout.argus.embabel.persistence;

import com.embabel.agent.api.reference.LlmReference;
import com.embabel.chat.*;
import com.embabel.common.ai.prompt.PromptContributor;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;

@Entity
@Table(
        name = "conversation",
        indexes = {
                @Index(name = "conversation_chat_platform_id_idx", columnList = "chat_platform_id"),
                @Index(name = "conversation_created_at_idx", columnList = "created_at"),
        }
)
public class PostgresqlConversation implements Conversation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_id", unique = true, nullable = false)
    @Getter
    private String conversationId;

    @Column(name = "chat_platform_id", nullable = false)
    @Getter
    private String chatPlatformId;

    @Enumerated(EnumType.STRING)
    @Column(name = "chat_platform", nullable = false, length = 32)
    @Getter
    private ChatPlatform chatPlatform;

    @Column(name = "created_at", nullable = false)
    @Getter
    private OffsetDateTime createdAt;

    @Column(name = "summary_conversation", columnDefinition = "text")
    @Getter
    @Setter
    private String summaryConversation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "messages", columnDefinition = "jsonb", nullable = false)
    private List<Message> messages = new LinkedList<>();

    @Setter
    @Transient
    private ConversationJpaRepository conversationJpaRepository;

    protected PostgresqlConversation() {
        // JPA no-arg constructor
    }

    public PostgresqlConversation(ConversationJpaRepository conversationJpaRepository,
            String chatPlatformId,
            String conversationId,
            ChatPlatform chatPlatform) {
        this.conversationJpaRepository = conversationJpaRepository;
        this.chatPlatformId = chatPlatformId;
        this.conversationId = conversationId;
        this.chatPlatform = chatPlatform;
        this.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        this.messages = new LinkedList<>();
    }

    // ---- Conversation interface ----

    @Override
    public @NotNull List<Message> getMessages() {
        return this.messages;
    }

    @Override
    public @NotNull AssetTracker getAssetTracker() {
        throw new UnsupportedOperationException(
                "This implementation does not support a dedicated AssetTracker; use AssetView instead.");
    }

    @Override
    public @NotNull Message addMessage(@NotNull Message message) {
        this.messages.add(message);
        PostgresqlConversation saved = this.conversationJpaRepository.save(this);
        return saved.getMessages().getLast();
    }

    @Override
    public @NotNull Conversation last(int n) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public @NotNull String getId() {
        return this.chatPlatformId;
    }

    @Override
    public boolean persistent() {
        return true;
    }

    // ---- AssetView interface ----

    @Override
    public @NonNull List<Asset> getAssets() {
        // No concrete asset types in argus yet; return empty list.
        return List.of();
    }

    @Override
    public @NonNull List<LlmReference> references() {
        return mostRecentlyAdded(5).getAssets()
                .stream()
                .map(Asset::reference)
                .toList();
    }

    @Override
    public @NonNull AssetView since(@NonNull Instant instant) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public @NonNull AssetView mostRecent(int n) {
        return mostRecentlyAdded(n);
    }

    @Override
    public @NonNull AssetView mostRecentlyAdded(int n) {
        List<AssetView> fromAssistant = this.messages.stream()
                .filter(m -> m instanceof EnhancedAssistantMessage)
                .map(m -> (EnhancedAssistantMessage) m)
                .sorted(Comparator.comparing(AssistantMessage::getTimestamp))
                .map(m -> (AssetView) m)
                .distinct()
                .limit(n)
                .toList();
        return new MergedAssetView(fromAssistant);
    }

    // ---- HasInfoString ----

    @Override
    public @NotNull String infoString(@Nullable Boolean verbose, int indent) {
        return promptContributor(new WindowingConversationFormatter()).contribution();
    }

    // ---- Conversation default helpers ----

    @Override
    public @NonNull PromptContributor promptContributor(@NonNull ConversationFormatter conversationFormatter) {
        return Conversation.super.promptContributor(conversationFormatter);
    }
}

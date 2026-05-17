package dev.datrollout.argus.embabel.persistence;

import com.embabel.chat.Conversation;
import com.embabel.chat.ConversationFactory;
import com.embabel.chat.ConversationStoreType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class PostgresqlConversationFactory implements ConversationFactory {

    private final ConversationJpaRepository conversationJpaRepository;

    @Override
    public @NotNull ConversationStoreType getStoreType() {
        return ConversationStoreType.STORED;
    }

    @Override
    public @NotNull Conversation create(@NotNull String conversationId) {
        PostgresqlConversation conversation = new PostgresqlConversation(
                this.conversationJpaRepository, conversationId, conversationId);
        this.conversationJpaRepository.save(conversation);
        return conversation;
    }

    @Override
    public @Nullable Conversation load(@NotNull String conversationId) {
        // Read newest-first to tolerate duplicate documents and keep service available.
        List<PostgresqlConversation> conversations = this.conversationJpaRepository
                .findAllByConversationIdOrderByCreatedAtDesc(conversationId);
        if (conversations == null || conversations.isEmpty()) {
            return null;
        }
        if (conversations.size() > 1) {
            // Keep a signal for cleanup while proceeding with the most recent record.
            log.warn("Detected duplicate conversations for conversationId={} count={}. Using latest record.",
                    conversationId, conversations.size());
        }
        PostgresqlConversation conversation = conversations.getFirst();
        conversation.setConversationJpaRepository(this.conversationJpaRepository);
        return conversation;
    }
}

package dev.datrollout.argus.embabel.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConversationJpaRepository extends JpaRepository<PostgresqlConversation, Long> {

    Optional<PostgresqlConversation> findByConversationId(String conversationId);

    // Duplicate rows can exist for the same conversation id; caller resolves latest safely.
    List<PostgresqlConversation> findAllByConversationIdOrderByCreatedAtDesc(String conversationId);

    List<PostgresqlConversation> findByChatPlatformIdAndCreatedAtBetween(
            String chatPlatformId, OffsetDateTime from, OffsetDateTime to);

    List<PostgresqlConversation> findByCreatedAtBetweenAndSummaryConversationIsNull(
            OffsetDateTime from, OffsetDateTime to);
}

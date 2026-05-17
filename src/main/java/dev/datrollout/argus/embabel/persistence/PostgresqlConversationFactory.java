package dev.datrollout.argus.embabel.persistence;

import com.embabel.chat.Conversation;
import com.embabel.chat.ConversationFactory;
import com.embabel.chat.ConversationStoreType;
import org.jspecify.annotations.NonNull;

public class PostgresqlConversationFactory implements ConversationFactory {
    @Override
    public @NonNull ConversationStoreType getStoreType() {
        return ConversationStoreType.STORED;
    }

    @Override
    public @NonNull Conversation create(@NonNull String id) {
        return null;
    }
}

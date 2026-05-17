package dev.datrollout.argus.embabel.persistence;

import com.embabel.chat.AssetTracker;
import com.embabel.chat.Conversation;
import com.embabel.chat.Message;
import org.jspecify.annotations.NonNull;

import java.util.List;

public class PostgresqlConversation implements Conversation {
    @Override
    public @NonNull List<Message> getMessages() {
        return List.of();
    }

    @Override
    public @NonNull AssetTracker getAssetTracker() {
        return null;
    }

    @Override
    public @NonNull Message addMessage(@NonNull Message message) {
        return null;
    }

    @Override
    public @NonNull Conversation last(int n) {
        return null;
    }

    @Override
    public @NonNull String getId() {
        return "";
    }

    @Override
    public boolean persistent() {
        return true;
    }
}

package dev.datrollout.argus.telegram;

import com.embabel.agent.api.identity.User;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@RequiredArgsConstructor
public class TelegramUser implements User {
    private final org.telegram.telegrambots.meta.api.objects.User telegramUser;

    @Override
    public @NotNull String getId() {
        return telegramUser.getId().toString();
    }

    @Override
    public @NotNull String getDisplayName() {
        return telegramUser.getFirstName() + " " + telegramUser.getLastName();
    }

    @Override
    public @NotNull String getUsername() {
        return telegramUser.getUserName();
    }

    @Override
    public @Nullable String getEmail() {
        return null;
    }
}

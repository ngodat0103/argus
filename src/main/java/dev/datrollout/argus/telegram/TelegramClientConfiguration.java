package dev.datrollout.argus.telegram;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Exposes the {@link TelegramClient} as a Spring bean so it can be injected into any service that
 * needs to send messages (e.g. {@code TotpSetupService}, {@code ConfirmationHandler}).
 *
 * <p>The bean is only created when {@code TELEGRAM_TOKEN} is set in the environment, consistent
 * with the {@code @ConditionalOnProperty} guard on {@code EmbabelSpringPolling}.
 */
@Configuration
public class TelegramClientConfiguration {

    @Bean
    public TelegramClient telegramClient() {
        String token = System.getenv("TELEGRAM_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "TELEGRAM_TOKEN environment variable is not set. Cannot create TelegramClient.");
        }
        return new OkHttpTelegramClient(token);
    }
}

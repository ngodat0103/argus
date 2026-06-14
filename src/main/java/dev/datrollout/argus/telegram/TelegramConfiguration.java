package dev.datrollout.argus.telegram;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.TelegramOkHttpClientFactory;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Exposes the {@link TelegramClient} as a Spring bean so it can be injected into any service that
 * needs to send messages (e.g. {@code TotpSetupService}, {@code ConfirmationHandler}).
 *
 * <p>The bean is only created when {@code TELEGRAM_TOKEN} is set in the environment, consistent
 * with the {@code @ConditionalOnProperty} guard on {@code EmbabelSpringPolling}.
 */
@Configuration(proxyBeanMethods = false)
public class TelegramConfiguration {

    @Bean
    public TelegramClient telegramClient() {
        String token = System.getenv("TELEGRAM_TOKEN");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException(
                    "TELEGRAM_TOKEN environment variable is not set. Cannot create TelegramClient.");
        }
        return new OkHttpTelegramClient(token);
    }

    @Bean
    TelegramBotsLongPollingApplication telegramBotsLongPollingApplication(
            ObjectMapper objectMapper, ScheduledThreadPoolExecutor scheduledThreadPoolExecutor) {
        Supplier<ObjectMapper> objectMapperSupplier = () -> objectMapper;
        Supplier<ScheduledExecutorService> scheduledExecutorServiceSupplier = () -> scheduledThreadPoolExecutor;
        var httpClientCreator = new TelegramOkHttpClientFactory.DefaultOkHttpClientCreator();
        return new TelegramBotsLongPollingApplication(
                objectMapperSupplier, httpClientCreator, scheduledExecutorServiceSupplier);
    }
}

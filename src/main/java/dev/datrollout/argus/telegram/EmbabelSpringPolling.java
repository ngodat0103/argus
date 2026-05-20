package dev.datrollout.argus.telegram;

import com.embabel.chat.ChatSession;
import com.embabel.chat.Chatbot;
import com.embabel.chat.UserMessage;
import dev.datrollout.argus.embabel.persistence.ConversationJpaRepository;
import dev.datrollout.argus.embabel.persistence.PostgresqlConversation;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@ConditionalOnProperty(name = "TELEGRAM_TOKEN")
@Slf4j
@Component
public class EmbabelSpringPolling implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {
    private static final ZoneOffset SESSION_ZONE = ZoneOffset.UTC;
    private static final DateTimeFormatter SESSION_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String TELEGRAM_SESSION_PREFIX = "telegram";
    private static final String CLEAR_COMMAND = "/clear";

    private final TelegramClient telegramClient;
    private final Chatbot chatbot;
    private final ConversationJpaRepository conversationJpaRepository;

    public EmbabelSpringPolling(Chatbot chatbot, ConversationJpaRepository conversationJpaRepository) {
        this.telegramClient = new OkHttpTelegramClient(this.getBotToken());
        this.chatbot = chatbot;
        this.conversationJpaRepository = conversationJpaRepository;
    }

    @Override
    public String getBotToken() {
        return System.getenv("TELEGRAM_TOKEN");
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {
        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        Message userMessage = update.getMessage();
        String chatPlatformId = userMessage.getChatId().toString();
        if (userMessage.isCommand() && isClearCommand(userMessage.getText())) {
            clearCurrentSession(chatPlatformId);
            sendText(chatPlatformId, "Session cleared. Send a message to start a new session.");
            return;
        }

        TelegramUser telegramUser = new TelegramUser(userMessage.getFrom());
        TelegramOutputChannel telegramOutputChannel = new TelegramOutputChannel(userMessage, this.telegramClient);
        String sessionId = resolveSessionId(chatPlatformId);
        ChatSession chatSession = this.chatbot.createSession(telegramUser, telegramOutputChannel, null, sessionId);
        UserMessage embabelUserMessage = new UserMessage(userMessage.getText());
        chatSession.onUserMessage(embabelUserMessage);
    }

    private String resolveSessionId(String chatPlatformId) {
        Optional<PostgresqlConversation> activeDailyConversation = findActiveDailyConversation(chatPlatformId);
        if (activeDailyConversation.isPresent()) {
            return activeDailyConversation.get().getConversationId();
        }
        LocalDate sessionDate = LocalDate.now(SESSION_ZONE);
        int nextSequence = nextDailySequence(chatPlatformId, sessionDate);
        return sessionId(chatPlatformId, sessionDate, nextSequence);
    }

    private Optional<PostgresqlConversation> findActiveDailyConversation(String chatPlatformId) {
        return activeDailyConversations(chatPlatformId, LocalDate.now(SESSION_ZONE)).stream()
                .filter(conversation -> isDailySessionId(conversation.getConversationId()))
                .findFirst();
    }

    private List<PostgresqlConversation> activeDailyConversations(String chatPlatformId, LocalDate sessionDate) {
        OffsetDateTime from = sessionDate.atStartOfDay().atOffset(SESSION_ZONE);
        OffsetDateTime to = from.plusDays(1);
        return this.conversationJpaRepository
                .findByChatPlatformIdAndCreatedAtBetweenAndDeletedAtIsNullOrderByCreatedAtDesc(
                        chatPlatformId, from, to);
    }

    private int nextDailySequence(String chatPlatformId, LocalDate sessionDate) {
        OffsetDateTime from = sessionDate.atStartOfDay().atOffset(SESSION_ZONE);
        OffsetDateTime to = from.plusDays(1);
        return this.conversationJpaRepository
                        .findByChatPlatformIdAndCreatedAtBetweenOrderByCreatedAtDesc(chatPlatformId, from, to)
                        .stream()
                        .map(PostgresqlConversation::getConversationId)
                        .filter(this::isDailySessionId)
                        .mapToInt(this::sessionSequence)
                        .max()
                        .orElse(0)
                + 1;
    }

    private void clearCurrentSession(String chatPlatformId) {
        activeDailyConversations(chatPlatformId, LocalDate.now(SESSION_ZONE)).stream()
                .findFirst()
                .ifPresent(conversation -> {
                    conversation.softDelete();
                    this.conversationJpaRepository.save(conversation);
                    log.info(
                            "Soft-deleted Telegram conversation {} for chat {}",
                            conversation.getConversationId(),
                            chatPlatformId);
                });
    }

    private void sendText(String chatPlatformId, String text) {
        try {
            this.telegramClient.execute(new SendMessage(chatPlatformId, text));
        } catch (TelegramApiException e) {
            log.warn("Failed to send Telegram message to chat {}", chatPlatformId, e);
        }
    }

    private static boolean isClearCommand(String text) {
        if (text == null) {
            return false;
        }
        String command = text.split("\\s+", 2)[0];
        return CLEAR_COMMAND.equals(command) || command.startsWith(CLEAR_COMMAND + "@");
    }

    private static String sessionId(String chatPlatformId, LocalDate sessionDate, int sequence) {
        return "%s:%s:%s:%d"
                .formatted(
                        TELEGRAM_SESSION_PREFIX, chatPlatformId, SESSION_DATE_FORMATTER.format(sessionDate), sequence);
    }

    private boolean isDailySessionId(String conversationId) {
        String[] parts = conversationId.split(":");
        if (parts.length != 4 || !TELEGRAM_SESSION_PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            SESSION_DATE_FORMATTER.parse(parts[2]);
            Integer.parseInt(parts[3]);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private int sessionSequence(String conversationId) {
        String[] parts = conversationId.split(":");
        return Integer.parseInt(parts[3]);
    }
}

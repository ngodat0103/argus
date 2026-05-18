package dev.datrollout.argus.telegram;

import com.embabel.chat.ChatSession;
import com.embabel.chat.Chatbot;
import com.embabel.chat.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@ConditionalOnProperty(name = "TELEGRAM_TOKEN")
@Slf4j
@Component
public class EmbabelSpringPolling implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private final TelegramClient telegramClient;
    private final Chatbot chatbot;

    public EmbabelSpringPolling(Chatbot chatbot) {
        this.telegramClient = new OkHttpTelegramClient(this.getBotToken());
        this.chatbot = chatbot;
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
        if (update.hasMessage() && update.getMessage().hasText()) {
            TelegramUser telegramUser = new TelegramUser(update.getMessage().getFrom());
            Message userMessage = update.getMessage();
            TelegramOutputChannel telegramOutputChannel = new TelegramOutputChannel(userMessage, this.telegramClient);
            String conversationId = update.getMessage().getChatId().toString();
            ChatSession chatSession =
                    this.chatbot.createSession(telegramUser, telegramOutputChannel, null, conversationId);
            UserMessage embabelUserMessage = new UserMessage(userMessage.getText());
            chatSession.onUserMessage(embabelUserMessage);
        }
    }
}

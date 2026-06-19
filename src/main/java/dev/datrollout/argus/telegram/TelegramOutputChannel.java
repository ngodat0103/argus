package dev.datrollout.argus.telegram;

import com.embabel.agent.api.channel.MessageOutputChannelEvent;
import com.embabel.agent.api.channel.OutputChannel;
import com.embabel.agent.api.channel.OutputChannelEvent;
import com.embabel.agent.api.channel.ProgressOutputChannelEvent;
import com.embabel.chat.AssistantMessage;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.api.methods.ParseMode;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Slf4j
@RequiredArgsConstructor
public class TelegramOutputChannel implements OutputChannel {
    @Getter
    private final Message userMessage;

    private final TelegramClient telegramClient;
    private Integer progressMessageId = null;

    @Override
    public void send(@NonNull OutputChannelEvent outputChannelEvent) {

        if (outputChannelEvent instanceof ProgressOutputChannelEvent progressOutputChannelEvent) {
            String progressMessage = progressOutputChannelEvent.getMessage();
            log.info("Sending progress message: {}", progressMessage);
            try {
                if (progressMessageId == null) {
                    Message sent = telegramClient.execute(
                            new SendMessage(userMessage.getChatId().toString(), progressMessage));
                    progressMessageId = sent.getMessageId();
                } else {
                    telegramClient.execute(EditMessageText.builder()
                            .chatId(userMessage.getChatId().toString())
                            .messageId(progressMessageId)
                            .text(progressMessage)
                            .build());
                }
            } catch (TelegramApiException e) {
                log.warn("Failed to send/update progress message", e);
            }
        } else if (outputChannelEvent instanceof MessageOutputChannelEvent messageOutputChannelEvent) {
            if (messageOutputChannelEvent.getMessage() instanceof AssistantMessage assistantMessage) {
                String text = assistantMessage.getTextContent();
                log.info("Sending message to Telegram chat {}", userMessage.getChatId());
                try {
                    sendFormatted(text);
                } catch (TelegramApiException e) {
                    throw new RuntimeException(e);
                }
                if (progressMessageId != null) {
                    try {
                        telegramClient.execute(DeleteMessage.builder()
                                .chatId(userMessage.getChatId().toString())
                                .messageId(progressMessageId)
                                .build());
                    } catch (TelegramApiException e) {
                        log.warn("Failed to delete progress message {}", progressMessageId, e);
                    } finally {
                        progressMessageId = null;
                    }
                }
            }
        }
    }

    private void sendFormatted(String text) throws TelegramApiException {
        SendMessage formatted =
                new SendMessage(userMessage.getChatId().toString(), TelegramMarkdownV2Formatter.format(text));
        formatted.setParseMode(ParseMode.MARKDOWNV2);
        try {
            telegramClient.execute(formatted);
        } catch (TelegramApiException e) {
            if (!isEntityParseError(e)) {
                throw e;
            }
            log.warn("Telegram rejected MarkdownV2 formatting, sending as plain text", e);
            telegramClient.execute(new SendMessage(userMessage.getChatId().toString(), text));
        }
    }

    private static boolean isEntityParseError(TelegramApiException e) {
        String message = e.getMessage();
        return message != null && message.toLowerCase().contains("can't parse");
    }
}

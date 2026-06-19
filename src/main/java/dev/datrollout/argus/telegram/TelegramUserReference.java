package dev.datrollout.argus.telegram;

import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.api.reference.LlmReference;
import com.embabel.agent.api.tool.Tool;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * LlmReference that exposes the current Telegram session to the LLM.
 *
 * Lifetime: one instance per incoming Telegram message/session.
 * The LLM uses these tools to resolve identity before routing incident
 * approvals, TOTP gates, and K8s alert subscriptions.
 */
public class TelegramUserReference implements LlmReference {

    private final Message userMessage;
    private final TelegramClient telegramClient;

    public TelegramUserReference(Message userMessage, TelegramClient telegramClient) {
        this.userMessage = userMessage;
        this.telegramClient = telegramClient;
    }

    // ── Identity tools ────────────────────────────────────────────────────────

    @LlmTool(name = "get_user_info", description = """
            Returns the Telegram identity of the current user: numeric user ID,
            username (if set), first name, last name, and whether they are a bot.
            Use this to confirm who is interacting with the agent before binding
            them to an alert subscription or approval gate.
            """)
    public TelegramUserInfo getUserInfo() {
        var from = userMessage.getFrom();
        return new TelegramUserInfo(
                from.getId(),
                from.getUserName(), // may be null — not all users set a username
                from.getFirstName(),
                from.getLastName(),
                from.getIsBot());
    }

    @LlmTool(name = "get_chat_id", description = """
            Returns the Telegram chat ID for the current conversation.
            This is the target needed to send follow-up messages, approval
            requests, or incident alerts back to this user or group.
            Store this value when registering a user for K8s incident notifications.
            """)
    public String getChatId() {
        return userMessage.getChatId().toString();
    }

    @LlmTool(name = "get_chat_type", description = """
            Returns the chat type: 'private', 'group', 'supergroup', or 'channel'.
            The agent must only allow approval gates and TOTP confirmations in
            'private' chats — never in groups where other members could intercept
            or spoof responses.
            """)
    public String getChatType() {
        return userMessage.getChat().getType();
    }

    @LlmTool(name = "send_confirmation_message", description = """
            Sends a plain-text confirmation message back to the current chat.
            Use this to acknowledge that a user has been registered for incident
            alerts or that their identity has been verified. Do NOT use this for
            sending incident details — that goes through the structured alert
            pipeline. Max length: 4096 characters (Telegram limit).
            """)
    public SendMessageResult sendConfirmationMessage(String text) {
        var send =
                SendMessage.builder().chatId(userMessage.getChatId()).text(text).build();
        try {
            telegramClient.execute(send);
            return new SendMessageResult(true, null);
        } catch (TelegramApiException e) {
            return new SendMessageResult(false, e.getMessage());
        }
    }

    // ── LlmReference contract ─────────────────────────────────────────────────

    @Override
    public @NotNull String notes() {
        return """
            USAGE NOTES:
            - Always call get_user_info first to confirm identity before any
              privileged operation (alert registration, approval gate binding).
            - get_chat_id returns the routing key used by the SRE agent to
              deliver future incident notifications to this user. Persist it.
            - Only proceed with approval-gate registration if get_chat_type
              returns 'private'. Reject group/channel contexts.
            - send_confirmation_message is fire-and-forget acknowledgment only.
              It is not a substitute for the structured incident alert pipeline.
            - User IDs are stable; usernames are mutable and must not be used
              as a sole identity anchor.
            """;
    }

    @Override
    public @NotNull String getDescription() {
        return """
            Provides identity and routing tools for the current Telegram session.
            Exposes the authenticated Telegram user's ID, username, and chat ID
            so the SRE agent can register them for Kubernetes incident alerts,
            bind them to an approval gate, or confirm their identity before a
            mutating cluster operation. This tool is scoped to the active session
            — it does not query any persistent store.
            """;
    }

    @Override
    public @NotNull String getName() {
        return "current_telegram_user";
    }

    @Override
    public @NotNull List<Tool> tools() {
        return Tool.safelyFromInstance(this);
    }

    // ── Value types ───────────────────────────────────────────────────────────

    public record TelegramUserInfo(
            Long userId,
            String username, // nullable
            String firstName,
            String lastName, // nullable
            boolean isBot) {}

    public record SendMessageResult(boolean success, String errorMessage // null on success
            ) {}
}

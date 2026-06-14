package dev.datrollout.argus.totp;

import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ForceReplyKeyboard;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Manages the TOTP confirmation state machine for pending high-risk operations.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>An agent tool registers a high-risk {@link PendingOperation} via
 *       {@link #storePendingOp(long, PendingOperation)}.
 *   <li>The bot sends a {@link ForceReplyKeyboard} prompt — Telegram UI automatically opens the
 *       reply input box, visually forcing the user to type their OTP.
 *   <li>When the user sends a {@code \d{6}} message the bot routes it to
 *       {@link #handleOtpInput(Message)}.
 *   <li>On success the operation is executed and the pending entry is cleared.
 *   <li>On failure the attempt counter is incremented; after 3 failures the op is cancelled.
 * </ol>
 *
 * <h3>Storage note</h3>
 * Pending operations are held in a {@link ConcurrentHashMap} (in-memory). This is suitable for
 * development and single-instance deployments. Migrate to Redis ({@code SETEX 120}) before running
 * multiple bot instances in production.
 */
@Component
@Slf4j
public class ConfirmationHandler {

    private final TelegramUserRepository telegramUserRepository;
    private final TotpVerifyService totpVerifyService;
    private final TelegramClient telegramClient;
    private final TotpProperties totpProperties;

    /** In-memory store of pending operations keyed by Telegram chat ID. */
    private final ConcurrentHashMap<Long, PendingOperation> pendingOps = new ConcurrentHashMap<>();

    public ConfirmationHandler(
            TelegramUserRepository telegramUserRepository,
            TotpVerifyService totpVerifyService,
            TelegramClient telegramClient,
            TotpProperties totpProperties) {
        this.telegramUserRepository = telegramUserRepository;
        this.totpVerifyService = totpVerifyService;
        this.telegramClient = telegramClient;
        this.totpProperties = totpProperties;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Stores a pending operation for the given chat and sends a {@link ForceReplyKeyboard} OTP
     * prompt.
     *
     * <p>The {@code ForceReply} markup causes Telegram to automatically open the reply input box
     * with a labelled placeholder, visually directing the user to enter their OTP without any
     * additional taps.
     *
     * <p>Called by agent tools after the user has confirmed via an inline button.
     *
     * @param chatId the Telegram chat ID
     * @param op     the operation awaiting TOTP confirmation
     */
    public void storePendingOp(long chatId, PendingOperation op) {
        pendingOps.put(chatId, op);
        log.info("Pending operation stored for chat {}: type={}, highRisk={}", chatId, op.getType(), op.isHighRisk());
        sendOtpPrompt(chatId);
    }

    /**
     * Handles a 6-digit OTP message from the user.
     *
     * <p>Called by the bot when a message matching {@code \\d{6}} is received.
     *
     * @param message the Telegram message containing the OTP
     */
    public void handleOtpInput(Message message) {
        long chatId = message.getChatId();
        String input = message.getText().trim();

        PendingOperation op = pendingOps.get(chatId);
        if (op == null) {
            // No pending operation — silently ignore so the agent can handle it instead
            log.debug("Received OTP-shaped input from chat {} but no pending operation found", chatId);
            return;
        }

        if (op.isExpired()) {
            pendingOps.remove(chatId);
            log.info("Pending operation expired for chat {}", chatId);
            sendText(chatId, "⏰ Session expired\\. Please start the operation again\\.");
            return;
        }

        String secret = telegramUserRepository
                .findByChatId(chatId)
                .map(TelegramUserEntity::getTotpSecret)
                .orElse(null);

        if (secret == null) {
            sendText(chatId, "⚠️ TOTP not configured\\. Run /setup first\\.");
            return;
        }

        boolean valid = totpVerifyService.verify(secret, input);

        if (valid) {
            pendingOps.remove(chatId);
            log.info("OTP verified for chat {}; executing operation type={}", chatId, op.getType());
            executeOperation(chatId, op);
            sendText(chatId, "✅ OTP verified\\. Operation executed\\.");
        } else {
            op.incrementAttempts();
            int remaining = PendingOperation.MAX_ATTEMPTS - op.getAttempts();
            if (remaining <= 0) {
                pendingOps.remove(chatId);
                log.warn("Too many failed OTP attempts for chat {}; operation cancelled", chatId);
                sendText(chatId, "❌ Too many failed attempts\\. Operation cancelled\\.");
            } else {
                log.debug("Invalid OTP for chat {}; {} attempt(s) remaining", chatId, remaining);
                sendText(chatId, "❌ Invalid code\\. " + remaining + " attempt\\(s\\) remaining\\.");
            }
        }
    }

    /** Returns {@code true} if there is an active (non-expired) pending operation for the chat. */
    public boolean hasPendingOperation(long chatId) {
        PendingOperation op = pendingOps.get(chatId);
        if (op == null) return false;
        if (op.isExpired()) {
            pendingOps.remove(chatId);
            return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Sends the OTP prompt using {@link ForceReplyKeyboard}.
     *
     * <p>Telegram displays a reply box pre-labelled with {@code inputFieldPlaceholder} text,
     * giving users an explicit, visually-guided place to type their 6-digit code.
     *
     * <p><strong>Note:</strong> {@code selective=false} is intentional — in private chats
     * selective has no effect, and {@code false} guarantees the ForceReply UI always appears.
     * Plain text (no {@code parseMode}) is used to avoid MarkdownV2 parse errors.
     */
    private void sendOtpPrompt(long chatId) {
        try {
            ForceReplyKeyboard forceReply = ForceReplyKeyboard.builder()
                    // Placeholder text shown inside the Telegram reply input box
                    .inputFieldPlaceholder("Enter 6-digit code…")
                    // selective=false → ForceReply always shows in private chats
                    .forceReply(true)
                    .selective(false)
                    .build();
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(totpProperties.getOtpPrompt())
                    .replyMarkup(forceReply)
                    .build());

            log.info("OTP ForceReply prompt sent to chat {}", chatId);
        } catch (TelegramApiException e) {
            // Escalate to ERROR — a silently-missing OTP prompt leaves the user stuck
            log.error("Failed to send OTP ForceReply prompt to chat {}", chatId, e);
        }
    }

    /**
     * Executes the confirmed operation.
     *
     * <p>Currently a stub — agent tool integrations will override behaviour based on
     * {@link PendingOperation#getType()} and {@link PendingOperation#getPayload()}.
     */
    private void executeOperation(long chatId, PendingOperation op) {
        log.info(
                "Executing confirmed operation for chat {}: type={}, payload={}",
                chatId,
                op.getType(),
                op.getPayload());
        // TODO: dispatch to the appropriate agent tool based on op.getType()
    }

    private void sendText(long chatId, String text) {
        try {
            telegramClient.execute(SendMessage.builder()
                    .chatId(chatId)
                    .text(text)
                    .parseMode("MarkdownV2")
                    .build());
        } catch (TelegramApiException e) {
            log.warn("Failed to send message to chat {}", chatId, e);
        }
    }
}

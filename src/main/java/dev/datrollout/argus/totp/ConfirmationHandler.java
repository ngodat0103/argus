package dev.datrollout.argus.totp;

import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Manages the TOTP confirmation state machine for pending high-risk operations.
 *
 * <h3>Flow</h3>
 * <ol>
 *   <li>An agent tool registers a high-risk {@link PendingOperation} via
 *       {@link #storePendingOp(long, PendingOperation)}.
 *   <li>The bot prompts the user for a 6-digit authenticator code.
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
@RequiredArgsConstructor
@Slf4j
public class ConfirmationHandler {

    private final TelegramUserRepository telegramUserRepository;
    private final TotpVerifyService totpVerifyService;
    private final TelegramClient telegramClient;

    /** In-memory store of pending operations keyed by Telegram chat ID. */
    private final ConcurrentHashMap<Long, PendingOperation> pendingOps = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Stores a pending operation for the given chat and sends the TOTP prompt.
     *
     * <p>Called by agent tools after the user has confirmed via an inline button.
     *
     * @param chatId the Telegram chat ID
     * @param op     the operation awaiting TOTP confirmation
     */
    public void storePendingOp(long chatId, PendingOperation op) {
        pendingOps.put(chatId, op);
        log.info("Pending operation stored for chat {}: type={}, highRisk={}", chatId, op.getType(), op.isHighRisk());
        sendText(
                chatId,
                "✅ QR code scanned\\? Enter the 6\\-digit code from your authenticator app to verify the setup:");
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

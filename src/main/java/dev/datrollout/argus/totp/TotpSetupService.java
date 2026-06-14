package dev.datrollout.argus.totp;

import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import java.io.ByteArrayInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Handles the one-time TOTP setup flow triggered by the {@code /setup-two-factor-authentication}
 * command.
 *
 * <p>The service generates a fresh Base32 secret, persists it against the user's Telegram chat ID,
 * builds an {@code otpauth://} QR code, and sends the QR image to the user so they can scan it
 * with any RFC 6238 authenticator app (Microsoft Authenticator, Google Authenticator, Aegis, etc.).
 *
 * <p><strong>Security note:</strong> The secret is currently stored in plain text. Encrypting it
 * at rest (AES-256 / KMS) is a high-priority follow-up (see Open Item #2 in the spec).
 */
@Service
@Slf4j
public class TotpSetupService {

    private final TelegramUserRepository telegramUserRepository;
    private final TelegramClient telegramClient;
    private final ConfirmationHandler confirmationHandler;

    @Value("${totp.issuer}")
    private String issuer;

    public TotpSetupService(
            TelegramUserRepository telegramUserRepository,
            TelegramClient telegramClient,
            ConfirmationHandler confirmationHandler) {
        this.telegramUserRepository = telegramUserRepository;
        this.telegramClient = telegramClient;
        this.confirmationHandler = confirmationHandler;
    }

    /**
     * Executes the full TOTP setup for the given Telegram chat.
     *
     * @param chatId the Telegram chat ID of the requesting user
     * @param label  human-readable account label shown in the authenticator app
     *               (typically the Telegram username or first name)
     */
    public void handleSetup(long chatId, String label) {
        try {
            // 1. Generate and persist secret
            String secret = new DefaultSecretGenerator().generate();
            TelegramUserEntity user =
                    telegramUserRepository.findByChatId(chatId).orElseGet(() -> new TelegramUserEntity(chatId));
            user.setTotpSecret(secret);
            telegramUserRepository.save(user);
            log.info("TOTP secret (re)generated for chat {}", chatId);

            // 2. Build otpauth:// QR code
            QrData qrData = new QrData.Builder()
                    .label(label)
                    .secret(secret)
                    .issuer(issuer)
                    .algorithm(HashingAlgorithm.SHA1)
                    .digits(6)
                    .period(30)
                    .build();

            byte[] qrImage = new ZxingPngQrGenerator().generate(qrData);

            // 3. Send QR code as a Telegram photo
            telegramClient.execute(SendPhoto.builder()
                    .chatId(chatId)
                    .photo(new InputFile(new ByteArrayInputStream(qrImage), "totp-setup.png"))
                    .caption("🔐 *Two\\-Factor Authentication Setup*\n\n"
                            + "Scan this QR code with your authenticator app:\n"
                            + "• Microsoft Authenticator: tap *\\+* → *Other account* → scan\n"
                            + "• Google Authenticator: tap *\\+* → *Scan QR code*\n"
                            + "• Any RFC 6238\\-compatible app works\n\n"
                            + "Once scanned, your app will show a 6\\-digit code that rotates every 30 s\\. "
                            + "You will be asked to enter this code to confirm high\\-risk operations\\.")
                    .parseMode("MarkdownV2")
                    .build());

            // Immediately test the OTP flow: prompt the user to verify their new code
            confirmationHandler.storePendingOp(
                    chatId, new PendingOperation("totp-verification-test", "setup-verification", false));
        } catch (TelegramApiException e) {
            log.error("Failed to send TOTP setup QR to chat {}", chatId, e);
            sendText(chatId, "❌ Failed to send QR code. Please try again.");
        } catch (Exception e) {
            log.error("TOTP setup error for chat {}", chatId, e);
            sendText(chatId, "❌ An unexpected error occurred during TOTP setup. Please try again.");
        }
    }

    // -------------------------------------------------------------------------

    private void sendText(long chatId, String text) {
        try {
            telegramClient.execute(new SendMessage(String.valueOf(chatId), text));
        } catch (TelegramApiException ex) {
            log.warn("Failed to send fallback text to chat {}", chatId, ex);
        }
    }
}

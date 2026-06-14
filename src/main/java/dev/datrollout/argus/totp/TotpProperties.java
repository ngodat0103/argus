package dev.datrollout.argus.totp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed configuration properties for the TOTP two-factor authentication feature.
 *
 * <p>Bound from the {@code totp.*} namespace in {@code application.yaml} (or profile-specific
 * overrides). Profile overrides:
 *
 * <ul>
 *   <li>{@code application-dev.yaml}  → issuer = "[dev] Argus Telegram Agent"
 *   <li>{@code application-prod.yaml} → issuer = "Argus Telegram Agent"
 * </ul>
 *
 * <p>Example YAML:
 *
 * <pre>{@code
 * totp:
 *   issuer: "Argus Telegram Agent"
 *   digits: 6
 *   period: 30
 *   otp-prompt: "🔐 Enter the 6-digit code from your authenticator app:"
 * }</pre>
 */
@ConfigurationProperties(prefix = "totp")
public class TotpProperties {

    /** Human-readable issuer name shown in the authenticator app. */
    private String issuer = "Argus Telegram Agent";

    /** Number of digits in the OTP code (RFC 6238 standard: 6). */
    private int digits = 6;

    /** Code validity window in seconds (RFC 6238 standard: 30). */
    private int period = 30;

    /**
     * Message sent to the user when they need to enter an OTP.
     * Plain text — no MarkdownV2 escaping needed. The ForceReply placeholder
     * ({@code "Enter 6-digit code…"}) provides additional visual guidance inside the input box.
     */
    private String otpPrompt =
            "\uD83D\uDD10 Two-factor authentication required\n\nOpen your authenticator app and enter the 6-digit code:";

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public int getDigits() {
        return digits;
    }

    public void setDigits(int digits) {
        this.digits = digits;
    }

    public int getPeriod() {
        return period;
    }

    public void setPeriod(int period) {
        this.period = period;
    }

    public String getOtpPrompt() {
        return otpPrompt;
    }

    public void setOtpPrompt(String otpPrompt) {
        this.otpPrompt = otpPrompt;
    }
}

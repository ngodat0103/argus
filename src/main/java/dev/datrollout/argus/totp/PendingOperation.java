package dev.datrollout.argus.totp;

import java.time.Duration;
import java.time.Instant;
import lombok.Getter;

/**
 * Represents a write operation that is pending two-factor confirmation.
 *
 * <p>An operation is created after the user approves an inline-button confirmation on a high-risk
 * action. The bot then prompts for a TOTP code, and this object tracks state until the code is
 * verified or the session expires.
 *
 * <p>Pending operations expire after 120 seconds. A maximum of 3 TOTP attempts are allowed before
 * the operation is cancelled.
 */
@Getter
public class PendingOperation {

    /** Maximum number of failed TOTP attempts before the operation is cancelled. */
    public static final int MAX_ATTEMPTS = 3;

    /** Time-to-live for a pending operation (seconds). */
    public static final long TTL_SECONDS = 120;

    private final String type;
    private final String payload;
    private final boolean highRisk;
    private final Instant createdAt;
    private int attempts = 0;

    public PendingOperation(String type, String payload, boolean highRisk) {
        this.type = type;
        this.payload = payload;
        this.highRisk = highRisk;
        this.createdAt = Instant.now();
    }

    /** Returns {@code true} if this operation has exceeded its 120-second TTL. */
    public boolean isExpired() {
        return Duration.between(createdAt, Instant.now()).toSeconds() > TTL_SECONDS;
    }

    public void incrementAttempts() {
        this.attempts++;
    }

    public int getAttempts() {
        return attempts;
    }
}

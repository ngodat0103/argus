# TOTP Two-Factor Authentication
## Feature Specification — Telegram AI Agent Write Operations

| Field | Value |
|---|---|
| Version | 1.0 |
| Status | Draft |
| Component | Telegram AI Agent — Confirmation Flow |
| Library | `dev.samstevens.totp:totp:1.7.1` |
| Authenticator | Microsoft Authenticator (or any RFC 6238 app) |

---

## 1. Overview

This document specifies the design and implementation of TOTP (Time-Based One-Time Password) two-factor authentication for the Telegram AI Agent. TOTP is applied as a second confirmation layer on high-risk write operations, complementing the existing inline-button confirmation flow.

> **Scope:** This spec covers TOTP only. The preceding inline-button confirmation step is documented separately in the Confirmation Flow spec.

### 1.1 What is TOTP?

TOTP (RFC 6238) is a two-party algorithm that generates a time-synchronized 6-digit code using only a shared secret key and the current Unix timestamp. No network communication occurs between the server and the authenticator app after the initial setup.

**Key properties:**

- **Offline** — the authenticator app requires no internet connection during code generation.
- **Stateless** — the server verifies codes by re-computing the expected value; no session or database lookup is needed for verification.
- **Time-bounded** — each code is valid for 30 seconds (±1 window for clock drift).
- **App-agnostic** — compatible with any RFC 6238 app: Microsoft Authenticator, Google Authenticator, Authy, Aegis, etc.

### 1.2 Why TOTP for this use case?

The Telegram AI agent executes write operations on behalf of the user (creating tasks, sending messages, deleting records, etc.). For high-risk operations, a single inline-button confirmation is insufficient because:

- Anyone with access to the Telegram session can tap Confirm.
- A compromised agent prompt could trigger a confirmation silently.
- There is no proof that the human — not a script — approved the action.

TOTP requires physical possession of the user's phone, providing a strong second factor without any external service dependency.

---

## 2. Architecture

### 2.1 Component overview

| Component | Responsibility |
|---|---|
| `TotpSetupService` | Generate Base32 secret, build QR code, send to user via Telegram |
| `TotpVerifyService` | Verify a 6-digit code against the stored secret |
| `ConfirmationHandler` | Route operations by risk level; manage pending-op state; orchestrate TOTP prompt |
| `UserRepository` | Persist and retrieve the TOTP secret per Telegram chat ID |
| `MyBot` (consumer) | Detect OTP input (`\d{6}`) and forward to `ConfirmationHandler` |

### 2.2 Confirmation flow by risk tier

| Risk tier | Examples | Confirmation steps |
|---|---|---|
| Low | Create task, send message, read query | Telegram inline button only |
| High | Delete records, bulk update, financial ops | Inline button → TOTP OTP |

> **Note:** Risk classification is determined by `isHighRisk()` on the `PendingOperation` model. See Section 4.2 for classification rules.

### 2.3 TOTP algorithm summary

Both the server and the authenticator app independently compute the same value using:

```
T       = floor(Unix timestamp / 30)          // time counter
hash    = HMAC-SHA1(Base32Decode(secret), T)  // 20-byte digest
offset  = hash[19] & 0x0F                     // dynamic offset
code    = ((hash[offset..offset+3] & 0x7FFFFFFF)) % 1_000_000
```

No network call is made during verification. The shared secret is the only coupling between the two sides.

---

## 3. Setup Flow

Setup is a one-time operation triggered by the `/setup` command. It must be completed before any TOTP verification is possible.

### 3.1 Sequence

1. User sends `/setup` to the bot.
2. Server generates a random Base32 secret (160-bit entropy via `DefaultSecretGenerator`).
3. Server persists the secret against the user's Telegram chat ID.
4. Server builds a QR code containing an `otpauth://` URI and sends it as a photo.
5. User opens Microsoft Authenticator → **(+)** → **Other account** → scans the QR.
6. Setup complete. Secret is now stored both on the server and locally in the app.

### 3.2 otpauth URI format

```
otpauth://totp/{issuer}:{label}
  ?secret={BASE32_SECRET}
  &issuer={issuer}
  &algorithm=SHA1
  &digits=6
  &period=30
```

### 3.3 Implementation

```java
@Service
public class TotpSetupService {

    private final UserRepository userRepository;
    private final TelegramClient telegramClient;

    public void handleSetup(long chatId, String userEmail) throws Exception {
        // 1. Generate and persist secret
        String secret = new DefaultSecretGenerator().generate();
        userRepository.saveTotpSecret(chatId, secret);

        // 2. Build QR code
        QrData qrData = new QrData.Builder()
            .label(userEmail)
            .secret(secret)
            .issuer("My Telegram Agent")
            .algorithm(HashingAlgorithm.SHA1)
            .digits(6)
            .period(30)
            .build();

        byte[] qrImage = new ZxingPngQrGenerator().generate(qrData);

        // 3. Send QR as Telegram photo
        telegramClient.execute(SendPhoto.builder()
            .chatId(chatId)
            .photo(new InputFile(
                new ByteArrayInputStream(qrImage), "totp-setup.png"))
            .caption("Scan with Microsoft Authenticator\n"
                   + "Tap (+) → Other account → Scan QR")
            .build());
    }
}
```

> **Security:** The Base32 secret must be stored encrypted at rest (e.g. AES-256 via Spring's `@Encrypted` or a KMS-backed column). Never log the secret value.

---

## 4. Verification Flow

### 4.1 Sequence

1. User taps **Confirm** on a high-risk inline-button prompt.
2. `ConfirmationHandler` detects `isHighRisk() == true`.
3. `PendingOperation` is stored in the pending ops map with a TTL.
4. Bot edits the message: *"Enter your 6-digit authenticator code:"*
5. User opens Microsoft Authenticator, reads the current code.
6. User sends the 6-digit code as a plain text message.
7. Bot detects `\d{6}` pattern and routes to `handleOtpInput()`.
8. `TotpVerifyService` checks `counter-1`, `counter`, `counter+1` windows.
9. If valid: execute the write operation, clear pending op, reply success.
10. If invalid: reply with error, leave pending op intact for retry (max 3 attempts).

### 4.2 Risk classification

| Operation type | Risk | Reason |
|---|---|---|
| DELETE (single record) | High | Irreversible data loss |
| DELETE (bulk / cascading) | High | Irreversible, wide impact |
| UPDATE (financial field) | High | Monetary consequence |
| UPDATE (bulk records) | High | Wide impact |
| CREATE (task / message) | Low | Reversible, limited scope |
| READ / search | None | No state change |

### 4.3 Implementation

```java
@Service
public class TotpVerifyService {

    private final CodeVerifier verifier;

    public TotpVerifyService() {
        this.verifier = new DefaultCodeVerifier(
            new DefaultCodeGenerator(),
            new SystemTimeProvider()  // ±1 window = 90s total tolerance
        );
    }

    public boolean verify(String base32Secret, String inputCode) {
        return verifier.isValidCode(base32Secret, inputCode);
    }
}
```

```java
// In ConfirmationHandler
public void handleOtpInput(Message message) throws TelegramApiException {
    long chatId = message.getChatId();
    String input = message.getText().trim();
    PendingOperation op = pendingOps.get(chatId);

    if (op == null) return;  // no pending op — ignore

    if (op.isExpired()) {
        pendingOps.remove(chatId);
        sendMessage(chatId, "Session expired. Please start over.");
        return;
    }

    String secret = userRepository.getTotpSecret(chatId);
    boolean valid = totpVerifyService.verify(secret, input);

    if (valid) {
        pendingOps.remove(chatId);
        executeOperation(op);
        sendMessage(chatId, "✅ OTP verified. Operation executed.");
    } else {
        op.incrementAttempts();
        if (op.getAttempts() >= 3) {
            pendingOps.remove(chatId);
            sendMessage(chatId, "❌ Too many failed attempts. Operation cancelled.");
        } else {
            sendMessage(chatId, "❌ Invalid code. "
                + (3 - op.getAttempts()) + " attempt(s) remaining.");
        }
    }
}
```

---

## 5. State Management

### 5.1 PendingOperation model

```java
public class PendingOperation {
    private final String type;       // e.g. "delete_task"
    private final String payload;    // serialized op params
    private final boolean highRisk;
    private final Instant createdAt;
    private int attempts = 0;

    public boolean isExpired() {
        return Duration.between(createdAt, Instant.now()).toSeconds() > 120;
    }

    public void incrementAttempts() { this.attempts++; }
    public int  getAttempts()       { return attempts; }
}
```

### 5.2 Storage recommendation

| Environment | Recommended store | TTL |
|---|---|---|
| Development / single instance | `ConcurrentHashMap` (in-memory) | 120 seconds (manual check) |
| Production / multi-instance | Redis with `SETEX` | 120 seconds (automatic) |

> **Important:** In-memory storage does not survive restarts and does not work across multiple bot instances. Migrate to Redis before deploying to production.

---

## 6. Bot Wiring

```java
@Override
public void consume(Update update) {

    // Handle inline button callbacks
    if (update.hasCallbackQuery()) {
        confirmationHandler.handleCallback(update.getCallbackQuery());
        return;
    }

    if (update.hasMessage() && update.getMessage().hasText()) {
        String text = update.getMessage().getText().trim();

        // Route 6-digit input to OTP handler first
        if (text.matches("\\d{6}")) {
            confirmationHandler.handleOtpInput(update.getMessage());
            return;
        }

        // Setup command
        if ("/setup".equals(text)) {
            totpSetupService.handleSetup(
                update.getMessage().getChatId(),
                "your-email@example.com"
            );
            return;
        }

        // Normal agent commands
        agentHandler.handle(update.getMessage());
    }
}
```

---

## 7. Dependencies

| Dependency | Version | Purpose |
|---|---|---|
| `dev.samstevens.totp:totp` | 1.7.1 | TOTP code generation and verification |
| `org.telegram:telegrambots-springboot-longpolling-starter` | 9.6.0 | Telegram bot long polling |
| `org.telegram:telegrambots-client` | 9.5.0 | Telegram API client |
| `com.google.zxing:core` | 3.5.x | QR code image generation (transitive via totp) |
| `spring-data-redis` (optional) | 3.x | Redis-backed pending op store for production |

```kotlin
// build.gradle.kts
implementation("dev.samstevens.totp:totp:1.7.1")
implementation("org.telegram:telegrambots-springboot-longpolling-starter:9.6.0")
implementation("org.telegram:telegrambots-client:9.5.0")
// optional for production state
implementation("org.springframework.boot:spring-boot-starter-data-redis")
```

---

## 8. Security Considerations

| Concern | Mitigation |
|---|---|
| Secret storage | Encrypt TOTP secret at rest (AES-256 / KMS). Never log it. |
| Replay attacks | `DefaultCodeVerifier` rejects codes already used within the current window. |
| Clock drift | ±1 time window (90s total tolerance) configured by default in `DefaultCodeVerifier`. |
| Brute force | Max 3 attempts per pending op; op cancelled after 3 failures. |
| Session expiry | Pending ops expire after 120 seconds. User must re-initiate the write command. |
| Secret loss | Back up the secret separately. If lost, re-run `/setup` to regenerate and re-scan. |
| Telegram session theft | TOTP is the second factor specifically to guard against compromised Telegram sessions. |

---

## 9. Open Items

| # | Item | Owner | Priority |
|---|---|---|---|
| 1 | Migrate pending ops store to Redis before production deploy | Backend | High |
| 2 | Encrypt TOTP secret column in `UserRepository` | Backend | High |
| 3 | Add `/setup` onboarding to bot welcome message | Backend | Medium |
| 4 | Consider TOTP secret rotation flow (`/reset-totp` command) | Backend | Low |
| 5 | Unit tests for `TotpVerifyService` (valid, invalid, expired) | Backend | High |

---

*End of document.*
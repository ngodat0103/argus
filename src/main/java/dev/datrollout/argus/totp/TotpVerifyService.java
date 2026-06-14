package dev.datrollout.argus.totp;

import dev.samstevens.totp.code.CodeVerifier;
import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.DefaultCodeVerifier;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.springframework.stereotype.Service;

/**
 * Verifies TOTP codes using the {@code dev.samstevens.totp} library.
 *
 * <p>The verifier checks the current time window as well as one window either side (±30 s) to
 * tolerate minor clock drift between the server and the authenticator app.
 */
@Service
public class TotpVerifyService {

    private final CodeVerifier verifier;

    public TotpVerifyService() {
        this.verifier = new DefaultCodeVerifier(new DefaultCodeGenerator(), new SystemTimeProvider());
    }

    /**
     * Verifies a 6-digit TOTP code against the stored Base32 secret.
     *
     * @param base32Secret the user's stored TOTP secret (Base32-encoded)
     * @param inputCode the 6-digit code supplied by the user
     * @return {@code true} if the code is valid for the current ±1 time window
     */
    public boolean verify(String base32Secret, String inputCode) {
        if (inputCode == null || inputCode.isBlank()) {
            return false;
        }
        return verifier.isValidCode(base32Secret, inputCode);
    }
}

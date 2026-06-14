package dev.datrollout.argus.totp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.code.HashingAlgorithm;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import dev.samstevens.totp.time.SystemTimeProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TotpVerifyService}.
 *
 * <p>These tests generate a live TOTP code using the same library and verify that the service
 * accepts it, rejects garbage input, and rejects obviously invalid codes.
 */
class TotpVerifyServiceTest {

    private TotpVerifyService totpVerifyService;

    /** A fixed Base32 secret used across all tests. */
    private static final String SECRET = "JBSWY3DPEHPK3PXP"; // well-known test vector

    @BeforeEach
    void setUp() {
        totpVerifyService = new TotpVerifyService();
    }

    @Test
    @DisplayName("verify: a freshly generated valid code is accepted")
    void verify_validCode_returnsTrue() throws CodeGenerationException {
        // Generate a live TOTP code using the same algorithm the service verifies against
        long timeStep = new SystemTimeProvider().getTime() / 30;
        String validCode = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6).generate(SECRET, timeStep);

        assertThat(totpVerifyService.verify(SECRET, validCode)).isTrue();
    }

    @Test
    @DisplayName("verify: a clearly wrong code is rejected")
    void verify_invalidCode_returnsFalse() {
        assertThat(totpVerifyService.verify(SECRET, "000000")).isFalse();
    }

    @Test
    @DisplayName("verify: non-numeric / garbage input is rejected")
    void verify_garbageInput_returnsFalse() {
        assertThat(totpVerifyService.verify(SECRET, "abcdef")).isFalse();
    }

    @Test
    @DisplayName("verify: null code does not throw — returns false")
    void verify_nullCode_returnsFalse() {
        // DefaultCodeVerifier returns false rather than throwing on null input
        assertThat(totpVerifyService.verify(SECRET, null)).isFalse();
    }

    @Test
    @DisplayName("verify: empty string is rejected")
    void verify_emptyCode_returnsFalse() {
        assertThat(totpVerifyService.verify(SECRET, "")).isFalse();
    }
}

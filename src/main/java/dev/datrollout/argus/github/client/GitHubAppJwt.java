package dev.datrollout.argus.github.client;

import io.jsonwebtoken.Jwts;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.Security;
import java.time.Instant;
import java.util.Date;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

/**
 * Generates the short-lived JWT that authenticates as the GitHub App itself.
 * This JWT is then exchanged for a per-installation access token.
 *
 * <p>The App ID is used as the {@code iss} claim, the JWT is signed with the app's
 * RSA private key (RS256), and GitHub caps its lifetime at 10 minutes.
 */
public class GitHubAppJwt {

    private final String appId;
    private final PrivateKey privateKey;

    public GitHubAppJwt(String appId, PrivateKey privateKey) {
        this.appId = appId;
        this.privateKey = privateKey;
    }

    /**
     * Builds a JWT with the required GitHub App claims.
     * The {@code iat} is backdated 60 seconds to tolerate clock skew.
     */
    public String generate() {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(appId)
                .issuedAt(Date.from(now.minusSeconds(60)))
                .expiration(Date.from(now.plusSeconds(600))) // 10 min max
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    /** Loads a PKCS#8 or PKCS#1 PEM from a filesystem path. */
    public static PrivateKey loadPrivateKeyFromPath(String path) {
        try {
            return parsePem(Files.readString(Path.of(path)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read GitHub App private key from path: " + path, e);
        }
    }

    /** Loads a PKCS#8 PEM from an in-memory string (e.g. from an env var or secret). */
    public static PrivateKey loadPrivateKeyFromString(String pem) {
        try {
            return parsePem(pem);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse GitHub App private key from configured string", e);
        }
    }

    private static PrivateKey parsePem(String pem) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            Object obj = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
            return switch (obj) {
                case PEMKeyPair kp -> converter.getPrivateKey(kp.getPrivateKeyInfo()); // PKCS#1
                case PrivateKeyInfo pki -> converter.getPrivateKey(pki); // PKCS#8
                default -> throw new IllegalStateException("Unsupported key: " + obj.getClass());
            };
        }
    }
}

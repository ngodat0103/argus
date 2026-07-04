package dev.datrollout.argus.github.client;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the GitHub App starter.
 *
 * <pre>
 * github:
 *   app:
 *     id: "123456"                 # App ID from the app settings page
 *     private-key: |               # PEM contents (or use private-key-path)
 *       -----BEGIN PRIVATE KEY-----
 *       ...
 *       -----END PRIVATE KEY-----
 *     private-key-path: /secrets/homelab-datnvm.pem
 *     token-refresh-margin: 5m     # refresh installation token this early
 *     api-url: https://api.github.com
 * </pre>
 */
@ConfigurationProperties(prefix = "github.app")
public class GitHubAppProperties {

    /** GitHub App ID (numeric). Found on the app settings page. Different from the client ID. */
    @Setter
    @Getter
    private String id;

    /** Private key PEM contents. Use this OR privateKeyPath, not both. */
    @Setter
    @Getter
    private String privateKey;

    /** Filesystem path to the private key PEM. Convenient for Kubernetes secret mounts. */
    @Setter
    @Getter
    private String privateKeyPath;

    /** GitHub API endpoint. Override for GitHub Enterprise (e.g. https://ghe.acme.com/api/v3). */
    @Setter
    @Getter
    private String apiUrl = "https://api.github.com";

    /** Refresh the installation token this long before it actually expires. */
    @Setter
    @Getter
    private Duration tokenRefreshMargin = Duration.ofMinutes(5);

    @Getter
    @Setter
    private String secret;
}

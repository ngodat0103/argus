package dev.datrollout.argus.github;

import java.time.Duration;
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
    private String id;

    /** Private key PEM contents. Use this OR privateKeyPath, not both. */
    private String privateKey;

    /** Filesystem path to the private key PEM. Convenient for Kubernetes secret mounts. */
    private String privateKeyPath;

    /** GitHub API endpoint. Override for GitHub Enterprise (e.g. https://ghe.acme.com/api/v3). */
    private String apiUrl = "https://api.github.com";

    /** Refresh the installation token this long before it actually expires. */
    private Duration tokenRefreshMargin = Duration.ofMinutes(5);

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(String privateKey) {
        this.privateKey = privateKey;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public void setPrivateKeyPath(String privateKeyPath) {
        this.privateKeyPath = privateKeyPath;
    }

    public String getApiUrl() {
        return apiUrl;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    public Duration getTokenRefreshMargin() {
        return tokenRefreshMargin;
    }

    public void setTokenRefreshMargin(Duration tokenRefreshMargin) {
        this.tokenRefreshMargin = tokenRefreshMargin;
    }
}

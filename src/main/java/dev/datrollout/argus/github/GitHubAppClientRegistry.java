package dev.datrollout.argus.github;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.kohsuke.github.GHApp;
import org.kohsuke.github.GHAppInstallation;
import org.kohsuke.github.GHAppInstallationToken;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central entry point for talking to GitHub as your App.
 *
 * <p>One GitHub App can be installed on many accounts/orgs. This registry lazily
 * creates and caches an authenticated {@link GitHub} client per installation
 * ({@code login -> client}), and transparently refreshes the underlying
 * installation token before it expires (installation tokens live ~1 hour).
 *
 * <p>Typical usage:
 * <pre>
 * GitHub gh = registry.clientFor("datnvm");
 * gh.getRepository("datnvm/homelab").createIssue("Hi").create();
 * </pre>
 */
public class GitHubAppClientRegistry {

    private static final Logger log = LoggerFactory.getLogger(GitHubAppClientRegistry.class);

    private final GitHubAppJwt jwtGenerator;
    private final String apiUrl;
    private final Duration refreshMargin;

    private final Map<String, CachedClient> cache = new ConcurrentHashMap<>();

    private record CachedClient(GitHub github, Instant expiresAt) {}

    public GitHubAppClientRegistry(GitHubAppJwt jwtGenerator, String apiUrl, Duration refreshMargin) {
        this.jwtGenerator = jwtGenerator;
        this.apiUrl = apiUrl;
        this.refreshMargin = refreshMargin;
    }

    /**
     * Returns an installation-scoped client for the given account/org login.
     * Cached and auto-refreshed.
     *
     * @param login the GitHub account or org that installed the app (e.g. "datnvm")
     */
    public GitHub clientFor(String login) {
        CachedClient cached = cache.get(login);
        if (cached == null || Instant.now().isAfter(cached.expiresAt().minus(refreshMargin))) {
            cached = refresh(login);
        }
        return cached.github();
    }

    /** Lists the logins of every account/org that has installed this app. */
    public List<String> listInstalledAccounts() {
        try {
            return appClient().getApp().listInstallations().toList().stream()
                    .map(i -> i.getAccount().getLogin())
                    .toList();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list app installations", e);
        }
    }

    /** Drops a cached client, forcing a fresh token on the next call. Use on uninstall webhooks. */
    public void evict(String login) {
        cache.remove(login);
        log.debug("Evicted cached GitHub client for '{}'", login);
    }

    /** Clears the whole cache. */
    public void evictAll() {
        cache.clear();
    }

    // --- internals -------------------------------------------------------

    private synchronized CachedClient refresh(String login) {
        // Double-check: another thread may have refreshed while we waited on the lock.
        CachedClient existing = cache.get(login);
        if (existing != null && Instant.now().isBefore(existing.expiresAt().minus(refreshMargin))) {
            return existing;
        }

        try {
            GHApp app = appClient().getApp();
            GHAppInstallation installation = app.listInstallations().toList().stream()
                    .filter(i -> i.getAccount().getLogin().equalsIgnoreCase(login))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException("GitHub App is not installed on account: " + login));

            GHAppInstallationToken token = installation.createToken().create();

            GitHub installationClient = new GitHubBuilder()
                    .withEndpoint(apiUrl)
                    .withAppInstallationToken(token.getToken())
                    .build();

            CachedClient fresh =
                    new CachedClient(installationClient, token.getExpiresAt().toInstant());
            cache.put(login, fresh);
            log.info("Refreshed GitHub installation token for '{}' (expires {})", login, fresh.expiresAt());
            return fresh;

        } catch (IOException e) {
            throw new IllegalStateException("Failed to create installation client for: " + login, e);
        }
    }

    /** A short-lived JWT-authenticated client, used only for app-level calls. */
    private GitHub appClient() {
        try {
            return new GitHubBuilder()
                    .withEndpoint(apiUrl)
                    .withJwtToken(jwtGenerator.generate())
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create app-level (JWT) GitHub client", e);
        }
    }
}

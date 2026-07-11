package dev.datrollout.argus.github.client;

import java.security.PrivateKey;
import org.kohsuke.github.GitHub;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Wires up the GitHub App beans automatically when this starter is on the classpath.
 *
 * <p>Activates only when {@code github.app.id} is present, and only if the user
 * hasn't already defined their own beans of the same type.
 */
@ConditionalOnClass(GitHub.class)
@ConditionalOnProperty(prefix = "github.app", name = "id")
@EnableConfigurationProperties(GitHubAppProperties.class)
@Configuration
public class GitHubAppAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public GitHubAppJwt gitHubAppJwt(GitHubAppProperties gitHubAppProperties) {
        PrivateKey key;
        if (StringUtils.hasText(gitHubAppProperties.getPrivateKey())) {
            key = GitHubAppJwt.loadPrivateKeyFromString(gitHubAppProperties.getPrivateKey());
        } else if (StringUtils.hasText(gitHubAppProperties.getPrivateKeyPath())) {
            key = GitHubAppJwt.loadPrivateKeyFromPath(gitHubAppProperties.getPrivateKeyPath());
        } else {
            throw new IllegalStateException("GitHub App private key not configured. Set either "
                    + "'github.app.private-key' or 'github.app.private-key-path'.");
        }
        return new GitHubAppJwt(gitHubAppProperties.getId(), key);
    }

    @Bean
    @ConditionalOnMissingBean
    public GitHubAppClientRegistry gitHubAppClientRegistry(GitHubAppJwt jwt, GitHubAppProperties props) {
        return new GitHubAppClientRegistry(jwt, props.getApiUrl(), props.getTokenRefreshMargin());
    }
}

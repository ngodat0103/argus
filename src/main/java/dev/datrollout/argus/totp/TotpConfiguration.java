package dev.datrollout.argus.totp;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration that activates the {@link TotpProperties} binding.
 *
 * <p>By annotating with {@link EnableConfigurationProperties} here (rather than on the main
 * application class), the TOTP configuration is self-contained within its own package.
 */
@Configuration
@EnableConfigurationProperties(TotpProperties.class)
public class TotpConfiguration {}

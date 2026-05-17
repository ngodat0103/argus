package dev.datrollout.argus.embabel.persistence.jackson;

import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link MessageJacksonModule} on the global Spring {@code ObjectMapper}.
 * <p>
 * Hibernate 6 picks up the primary {@code ObjectMapper} bean for JSONB
 * {@code @JdbcTypeCode(SqlTypes.JSON)} columns, so adding the module here is
 * sufficient — no separate {@code FormatMapper} wiring is required.
 * <p>
 * Timestamps are written as ISO-8601 strings (not epoch numbers) to keep the
 * JSONB column human-readable and consistent with the read side.
 */
@Configuration(proxyBeanMethods = false)
public class JpaJsonObjectMapperConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer messageJacksonModuleCustomizer() {
        return builder -> builder
                .modules(new MessageJacksonModule())
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}

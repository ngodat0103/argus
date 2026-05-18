package dev.datrollout.argus.embabel.persistence.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.type.format.jackson.JacksonJsonFormatMapper;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link MessageJacksonModule} into both the Spring {@code ObjectMapper}
 * and Hibernate's JSON type mapper so that {@code List<Message>} JSONB columns
 * round-trip correctly through {@code @JdbcTypeCode(SqlTypes.JSON)}.
 * <p>
 * Spring Boot 3.x does <strong>not</strong> auto-wire the primary
 * {@code ObjectMapper} into Hibernate 6 (tracked as spring-boot#33870), so we
 * explicitly register a {@link JacksonJsonFormatMapper} backed by the
 * Spring-managed mapper via {@link HibernatePropertiesCustomizer}.
 * <p>
 * Timestamps are written as ISO-8601 strings (not epoch numbers) to keep the
 * JSONB column human-readable and consistent with the read side.
 */
@Configuration(proxyBeanMethods = false)
public class JpaJsonObjectMapperConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer messageJacksonModuleCustomizer() {
        // modulesToInstall() ADDS to the auto-registered modules (e.g. JavaTimeModule);
        // modules() would REPLACE them and break Instant/OffsetDateTime handling.
        return builder -> builder.modulesToInstall(new MessageJacksonModule())
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    public HibernatePropertiesCustomizer jsonFormatMapperCustomizer(ObjectMapper objectMapper) {
        return properties ->
                properties.put(AvailableSettings.JSON_FORMAT_MAPPER, new JacksonJsonFormatMapper(objectMapper));
    }
}

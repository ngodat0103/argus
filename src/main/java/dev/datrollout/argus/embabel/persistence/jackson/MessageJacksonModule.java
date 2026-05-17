package dev.datrollout.argus.embabel.persistence.jackson;

import com.embabel.agent.core.hitl.Awaitable;
import com.embabel.chat.Message;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.time.Instant;

/**
 * Jackson module that wires polymorphic deserialization for embabel's {@link Message} hierarchy
 * and registers Java-time support. Intended for the Hibernate JSON type mapper so that
 * {@code List<Message>} JSONB columns round-trip correctly.
 * <p>
 * - {@link Message}: custom deserializer dispatches on the {@code role} field.
 * - {@link com.embabel.chat.AssistantMessage}: {@code awaitable} is suppressed (no HITL in argus).
 * - {@link com.embabel.chat.UserMessage}: {@code @JsonCreator} added via mixin for deserialization.
 * - {@link com.embabel.chat.SystemMessage}: {@code @JsonCreator} added via mixin for deserialization.
 * <p>
 * {@link com.embabel.chat.ContentPart} is NOT configured here because embabel already annotates it
 * with {@code @JsonTypeInfo(use = DEDUCTION)} on the sealed interface itself.
 */
public class MessageJacksonModule extends SimpleModule {

    public MessageJacksonModule() {
        super("embabel-message-module");
        addDeserializer(Message.class, new MessageDeserializer());
    }

    @Override
    public void setupModule(SetupContext context) {
        super.setupModule(context);
        context.registerSubtypes(
                com.embabel.chat.UserMessage.class,
                com.embabel.chat.SystemMessage.class,
                dev.datrollout.argus.embabel.persistence.EnhancedAssistantMessage.class
        );
        context.setMixInAnnotations(com.embabel.chat.AssistantMessage.class, AssistantMessageMixin.class);
        context.setMixInAnnotations(com.embabel.chat.UserMessage.class, UserMessageMixin.class);
        context.setMixInAnnotations(com.embabel.chat.SystemMessage.class, SystemMessageMixin.class);
    }

    // ---- Mixins ----

    abstract static class AssistantMessageMixin {
        /** Suppress awaitable from JSONB — no HITL persistence in argus. */
        @JsonIgnore
        abstract Awaitable<?, ?> getAwaitable();
    }

    abstract static class UserMessageMixin {
        @JsonCreator
        UserMessageMixin(
                @JsonProperty("parts") java.util.List<com.embabel.chat.ContentPart> parts,
                @JsonProperty("name") String name,
                @JsonProperty("timestamp") Instant timestamp) {
        }
    }

    abstract static class SystemMessageMixin {
        @JsonCreator
        SystemMessageMixin(
                @JsonProperty("content") String content,
                @JsonProperty("timestamp") Instant timestamp) {
        }
    }
}

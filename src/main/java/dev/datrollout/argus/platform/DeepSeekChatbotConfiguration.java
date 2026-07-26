package dev.datrollout.argus.platform;

import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.api.invocation.UtilityInvocation;
import com.embabel.agent.api.models.DeepSeekModels;
import com.embabel.agent.config.models.deepseek.DeepSeekModelsConfig;
import com.embabel.agent.config.models.deepseek.DeepSeekProperties;
import com.embabel.agent.core.*;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.chat.Chatbot;
import com.embabel.chat.ConversationFactory;
import com.embabel.chat.agent.AgentProcessChatbot;
import com.embabel.chat.agent.AgentSource;
import com.embabel.chat.agent.ListenerProvider;
import com.embabel.common.ai.model.OptionsConverter;
import dev.datrollout.argus.ThreadConfiguration;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = true)
@ConditionalOnBean(ConversationFactory.class)
public class DeepSeekChatbotConfiguration extends DeepSeekModelsConfig {
    private final DeepSeekProperties deepSeekProperties;
    private final ExecutorService executorService;

    public DeepSeekChatbotConfiguration(
            @Value("DEEPSEEK_BASE_URL") String envBaseUrl,
            @Value("DEEPSEEK_API_KEY") String envApiKey,
            @NotNull DeepSeekProperties deepSeekProperties,
            @Qualifier(ThreadConfiguration.VIRTUAL_THREAD) ExecutorService executorService,
            @NotNull ObjectProvider<ObservationRegistry> observationRegistry) {
        super(envBaseUrl, envApiKey, deepSeekProperties, observationRegistry);
        this.deepSeekProperties = deepSeekProperties;
        this.executorService = executorService;
    }

    @Bean
    RestClient.Builder restClientBuilder() {
        HttpClient httpClient = HttpClient.newBuilder()
                .executor(executorService)
                .connectTimeout(Duration.ofSeconds(30))
                .version(HttpClient.Version.HTTP_2)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMinutes(3));
        return RestClient.builder().requestFactory(requestFactory);
    }

    @Bean
    Chatbot chatbot(ConversationFactory conversationFactory, AgentPlatform agentPlatform) {
        Verbosity verbosity =
                new Verbosity().withDebug(true).withShowLlmResponses(true).withShowPrompts(true);
        AgentSource agentSource = _ -> {
            ProcessOptions processOptions = new ProcessOptions()
                    .withAdditionalEarlyTerminationPolicy(EarlyTerminationPolicy.maxTokens(200_000))
                    .withAdditionalEarlyTerminationPolicy(EarlyTerminationPolicy.maxActions(10));

            return UtilityInvocation.on(agentPlatform)
                    .withProcessOptions(processOptions)
                    .createPlatformAgent();
        };
        ListenerProvider listenerProvider = (_, _) -> List.of(); // Todo Temporary NoOps
        return new AgentProcessChatbot(
                agentPlatform, agentSource, conversationFactory, listenerProvider, PlannerType.UTILITY, verbosity);
    }

    @Bean
    DeepSeekApi deepSeekApi() {
        assert deepSeekProperties.getApiKey() != null;
        return DeepSeekApi.builder()
                .apiKey(deepSeekProperties.getApiKey())
                .baseUrl(deepSeekProperties.getBaseUrl())
                .restClientBuilder(this.restClientBuilder())
                .build();
    }

    @Override
    public @NotNull SpringAiLlmService deepSeekReasoner() {
        String reasoningModelName = "deepseek-v4-pro";
        DeepSeekApi deepSeekApi = this.deepSeekApi();
        DeepSeekChatModel deepSeekChatModel = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(
                        DeepSeekChatOptions.builder().model(reasoningModelName).build())
                .retryTemplate(deepSeekProperties.retryTemplate(reasoningModelName))
                .build();
        return getSpringAiLlmService(deepSeekChatModel);
    }

    private static @NotNull SpringAiLlmService getSpringAiLlmService(DeepSeekChatModel deepSeekChatModel) {
        OptionsConverter<DeepSeekChatOptions> deepSeekOptionsConverter = options -> DeepSeekChatOptions.builder()
                .frequencyPenalty(options.getFrequencyPenalty())
                .maxTokens(options.getMaxTokens())
                .presencePenalty(options.getPresencePenalty())
                .temperature(options.getTemperature())
                .topP(options.getTopP())
                .build();
        return new SpringAiLlmService(
                "deepseek-reasoner", DeepSeekModels.PROVIDER, deepSeekChatModel, deepSeekOptionsConverter);
    }

    @Override
    public @NotNull SpringAiLlmService deepSeekChat() {
        return super.deepSeekChat();
    }
}

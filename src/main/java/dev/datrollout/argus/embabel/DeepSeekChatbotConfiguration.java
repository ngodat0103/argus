package dev.datrollout.argus.embabel;

import com.embabel.agent.api.models.DeepSeekModels;
import com.embabel.agent.config.models.deepseek.DeepSeekModelsConfig;
import com.embabel.agent.config.models.deepseek.DeepSeekProperties;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.Verbosity;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.chat.Chatbot;
import com.embabel.chat.ConversationFactory;
import com.embabel.chat.agent.AgentProcessChatbot;
import com.embabel.common.ai.model.OptionsConverter;
import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = true)
@ConditionalOnBean(ConversationFactory.class)
public class DeepSeekChatbotConfiguration extends DeepSeekModelsConfig {
    private final DeepSeekProperties deepSeekProperties;

    public DeepSeekChatbotConfiguration(
            @Value("DEEPSEEK_BASE_URL") String envBaseUrl,
            @Value("DEEPSEEK_API_KEY") String envApiKey,
            @NotNull DeepSeekProperties properties,
            @NotNull ObjectProvider<ObservationRegistry> observationRegistry,
            ApplicationContext applicationContext) {
        super(envBaseUrl, envApiKey, properties, observationRegistry);
        this.deepSeekProperties = properties;
    }

    @Bean
    RestClient.Builder restClientBuilder() {
        ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        HttpClient httpClient = HttpClient.newBuilder()
                .executor(executorService)
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMinutes(5));
        return RestClient.builder().requestFactory(requestFactory);
    }

    @Bean
    Chatbot chatbot(ConversationFactory conversationFactory, AgentPlatform agentPlatform) {
        Verbosity verbosity =
                new Verbosity().withDebug(true).withShowLlmResponses(true).withShowPrompts(true);
        Chatbot chatbot = AgentProcessChatbot.utilityFromPlatform(agentPlatform, conversationFactory, verbosity);
        return chatbot;
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
        DeepSeekApi deepSeekApi = this.deepSeekApi();
        DeepSeekChatModel deepSeekChatModel = DeepSeekChatModel.builder()
                .deepSeekApi(deepSeekApi)
                .defaultOptions(DeepSeekChatOptions.builder()
                        .model(DeepSeekApi.ChatModel.DEEPSEEK_REASONER)
                        .build())
                .retryTemplate(deepSeekProperties.retryTemplate(DeepSeekApi.ChatModel.DEEPSEEK_REASONER.name()))
                .build();
        SpringAiLlmService springAiLlmService = getSpringAiLlmService(deepSeekChatModel);
        return springAiLlmService;
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

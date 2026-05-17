package dev.datrollout.argus.embabel;


import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.Verbosity;
import com.embabel.chat.Chatbot;
import com.embabel.chat.ConversationFactory;
import com.embabel.chat.agent.AgentProcessChatbot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(ConversationFactory.class)
public class ChatBotConfiguration {
    @Bean
    Chatbot chatbot(ConversationFactory conversationFactory, AgentPlatform agentPlatform){
        Verbosity verbosity = new Verbosity()
                .withDebug(true)
                .withShowLlmResponses(true)
                .withShowPrompts(true);
       Chatbot chatbot = AgentProcessChatbot.utilityFromPlatform(agentPlatform,conversationFactory,verbosity);
       return chatbot;
    }
}

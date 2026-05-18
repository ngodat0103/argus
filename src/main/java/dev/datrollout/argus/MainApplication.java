package dev.datrollout.argus;

import com.embabel.agent.autoconfigure.models.deepseek.AgentDeepSeekAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = {AgentDeepSeekAutoConfiguration.class})
public class MainApplication {

    static void main(String[] args) {
        SpringApplication.run(MainApplication.class, args);
    }
}

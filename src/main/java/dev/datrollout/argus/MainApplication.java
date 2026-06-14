package dev.datrollout.argus;

import com.embabel.agent.autoconfigure.models.deepseek.AgentDeepSeekAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;

@SpringBootApplication(exclude = {AgentDeepSeekAutoConfiguration.class, TaskExecutionAutoConfiguration.class})
public class MainApplication {

    static void main(String[] args) {
        SpringApplication.run(MainApplication.class, args);
    }
}

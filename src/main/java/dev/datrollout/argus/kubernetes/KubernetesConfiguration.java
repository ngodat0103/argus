package dev.datrollout.argus.kubernetes;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration(proxyBeanMethods = false)
public class KubernetesConfiguration {

    @Bean
    KubernetesClient kubernetesClient() {
        ThreadFactory threadFactory =
                Thread.ofPlatform().name("kubernetesClient", 0).factory();
        ExecutorService executorService = Executors.newFixedThreadPool(10, threadFactory);
        KubernetesClient kubernetesClient =
                new KubernetesClientBuilder().withTaskExecutor(executorService).build();
        return kubernetesClient;
    }
}

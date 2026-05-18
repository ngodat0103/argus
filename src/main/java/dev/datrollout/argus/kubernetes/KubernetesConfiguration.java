package dev.datrollout.argus.kubernetes;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class KubernetesConfiguration {

    @Bean
    KubernetesClient kubernetesClient() {
        ThreadFactory threadFactory =
                Thread.ofPlatform().name("kubernetesClient", 0).factory();
        ExecutorService executorService = Executors.newFixedThreadPool(10, threadFactory);
        return new KubernetesClientBuilder().withTaskExecutor(executorService).build();
    }
}

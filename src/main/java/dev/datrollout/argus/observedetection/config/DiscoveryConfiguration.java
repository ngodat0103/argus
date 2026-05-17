package dev.datrollout.argus.observedetection.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DiscoveryProperties.class)
public class DiscoveryConfiguration {

    @Bean
    RestClient discoveryRestClient(DiscoveryProperties properties) {
        return RestClient.builder()
                .defaultHeader("Accept", "application/json")
                .build();
    }

    @Bean(name = "probeExecutor", destroyMethod = "shutdown")
    ExecutorService probeExecutor() {
        ThreadFactory threadFactory = Thread.ofPlatform()
                .name("probe-worker-", 0)
                .factory();
        return Executors.newFixedThreadPool(10, threadFactory);
    }
}

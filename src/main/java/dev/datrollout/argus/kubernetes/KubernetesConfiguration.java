package dev.datrollout.argus.kubernetes;

import dev.datrollout.argus.ThreadConfiguration;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.VersionInfo;
import java.util.concurrent.ExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Slf4j
@Configuration(proxyBeanMethods = false)
public class KubernetesConfiguration {

    @Bean
    @Profile("dev")
    KubernetesClient kubernetesClient(@Qualifier(ThreadConfiguration.WORKER_THREAD) ExecutorService executorService) {
        KubernetesClient kubernetesClient = new KubernetesClientBuilder()
                .withConfig(Config.autoConfigure("kubernetes-super-admin@cluster.local"))
                .withTaskExecutor(executorService)
                .build();
        logClientInfo(kubernetesClient, "dev");
        return kubernetesClient;
    }

    @Bean
    @Profile("prod")
    KubernetesClient kubernetesClientProd(
            @Qualifier(ThreadConfiguration.WORKER_THREAD) ExecutorService executorService) {
        KubernetesClient kubernetesClient =
                new KubernetesClientBuilder().withTaskExecutor(executorService).build();
        logClientInfo(kubernetesClient, "prod");
        return kubernetesClient;
    }

    private void logClientInfo(KubernetesClient client, String profile) {
        String masterUrl = client.getMasterUrl().toString();
        String namespace = client.getNamespace();
        try {
            VersionInfo versionInfo = client.getKubernetesVersion();
            log.info(
                    "KubernetesClient initialized [profile={}, masterUrl={}, namespace={}, serverVersion={}.{} ({})]",
                    profile,
                    masterUrl,
                    namespace,
                    versionInfo.getMajor(),
                    versionInfo.getMinor(),
                    versionInfo.getGitVersion());
        } catch (Exception e) {
            log.warn(
                    "KubernetesClient initialized [profile={}, masterUrl={}, namespace={}], but failed to fetch server version: {}",
                    profile,
                    masterUrl,
                    namespace,
                    e.getMessage());
        }
    }
}

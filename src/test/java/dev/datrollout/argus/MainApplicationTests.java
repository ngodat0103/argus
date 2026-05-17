package dev.datrollout.argus;

import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = "discovery.watcher.enabled=false")
class MainApplicationTests {

    @MockitoBean
    KubernetesClient kubernetesClient;

    @Test
    void contextLoads() {
    }
}

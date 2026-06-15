package dev.datrollout.argus.kubernetes.detector;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OomKilledDetector implements SmartLifecycle {

    private final KubernetesClient kubernetesClient;
    private final MemoryContainerWatcher memoryContainerWatcher;
    private Watch containerWatch;
    private Watch kubeletWatch;
    private volatile boolean running = false;

    @Override
    public void start() {
        this.containerWatch = kubernetesClient.pods().inAnyNamespace().watch(this.memoryContainerWatcher);
        log.info("Started OomKillWatcher");
        running = true;
    }

    private void inspect(Pod pod) {
        // Todo implement later
    }

    private List<ContainerStatus> allContainerStatuses(PodStatus status) {
        var all = new ArrayList<ContainerStatus>();
        if (status.getInitContainerStatuses() != null) all.addAll(status.getInitContainerStatuses());
        if (status.getContainerStatuses() != null) all.addAll(status.getContainerStatuses());
        return all;
    }

    @Override
    public void stop() {
        if (containerWatch != null) containerWatch.close();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    } // start last
}

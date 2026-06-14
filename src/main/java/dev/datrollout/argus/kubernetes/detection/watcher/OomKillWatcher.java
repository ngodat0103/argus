package dev.datrollout.argus.kubernetes.detection.watcher;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class OomKillWatcher implements SmartLifecycle {

    private final KubernetesClient kubernetesClient;
    private final ApplicationEventPublisher publisher;

    // track last-seen restartCount per container to avoid duplicate fires
    private final ConcurrentHashMap<String, Integer> restartCounts = new ConcurrentHashMap<>();

    private Watch watch;
    private volatile boolean running = false;

    @Override
    public void start() {
        watch = kubernetesClient.pods().inAnyNamespace().watch(new Watcher<>() {
            @Override
            public boolean reconnecting() {
                return true;
            }

            @Override
            public void eventReceived(Action action, Pod pod) {
                if (action != Action.MODIFIED) return;
                inspect(pod);
            }

            @Override
            public void onClose(WatcherException cause) {
                if (cause != null) {
                    // Fabric8 auto-reconnects on HTTP 410 Gone — this fires on
                    // non-recoverable errors only. Log + alert, don't re-register here.
                }
            }
        });
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
        if (watch != null) watch.close();
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

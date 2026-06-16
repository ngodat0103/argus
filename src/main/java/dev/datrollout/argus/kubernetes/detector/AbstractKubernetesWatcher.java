package dev.datrollout.argus.kubernetes.detector;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Base class for Kubernetes resource watchers that manage their own watch
 * lifecycle as Spring {@link SmartLifecycle} beans.
 *
 * <p>Encapsulates the boilerplate shared by every watcher: the {@link Watch}
 * subscription, the running flag, automatic reconnection on HTTP 410 Gone, and
 * the graceful/failure {@code onClose} handlers. Subclasses only implement
 * {@link #startWatch()} (how to subscribe to their resource type) and
 * {@link #eventReceived(Action, Object)} (what to do with each event).
 */
@Slf4j
public abstract class AbstractKubernetesWatcher<T extends HasMetadata> implements Watcher<T>, SmartLifecycle {

    private Watch watch;
    private volatile boolean running = false;

    /**
     * Establishes the watch on the resource type of interest, e.g.
     * {@code kubernetesClient.pods().inAnyNamespace().watch(this)}.
     */
    protected abstract Watch startWatch();

    /** Human-readable name used in log messages. Defaults to the simple class name. */
    protected String watcherName() {
        return getClass().getSimpleName();
    }

    @Override
    public boolean reconnecting() {
        log.debug("{}: reconnecting=true, watch will survive HTTP 410 Gone", watcherName());
        return true;
    }

    @Override
    public void onClose() {
        log.info("{}: graceful close — watch lifecycle ended by owner", watcherName());
    }

    @Override
    public void onClose(WatcherException cause) {
        if (cause == null) {
            log.debug("{}.onClose(WatcherException) called with null cause — treating as graceful", watcherName());
            return;
        }
        if (cause.isHttpGone()) {
            log.warn("{}: watch expired (HTTP 410 Gone), Fabric8 will reconnect automatically", watcherName());
        } else {
            log.error(
                    "{}: non-recoverable watch failure — publishing WatcherFailedEvent for agent triage",
                    watcherName(),
                    cause);
        }
    }

    @Override
    public void start() {
        this.watch = startWatch();
        running = true;
        log.info("Started {}", watcherName());
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
}

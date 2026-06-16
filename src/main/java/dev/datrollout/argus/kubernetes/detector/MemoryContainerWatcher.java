package dev.datrollout.argus.kubernetes.detector;

import dev.datrollout.argus.kubernetes.phase.runtime.ContainerMemoryKillEventWrapper;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryContainerWatcher extends AbstractKubernetesWatcher<Pod> {

    private final ApplicationEventPublisher applicationEventPublisher;
    private final KubernetesClient kubernetesClient;
    private final ConcurrentHashMap<String, Integer> seenRestartCounts = new ConcurrentHashMap<>();

    @Override
    protected Watch startWatch() {
        return kubernetesClient.pods().inAnyNamespace().watch(this);
    }

    @Override
    public void eventReceived(Action action, Pod pod) {
        var ns = pod.getMetadata().getNamespace();
        var name = pod.getMetadata().getName();

        log.debug("eventReceived: action={} pod={}/{}", action, ns, name);

        if (action != Action.MODIFIED) {
            log.debug("Skipping non-MODIFIED event: action={} pod={}/{}", action, ns, name);
            return;
        }

        var candidates = detectOomKilledContainers(pod);
        log.debug(
                "OOMKill scan complete: pod={}/{} totalContainersChecked={} oomCandidates={}",
                ns,
                name,
                countAllContainers(pod.getStatus()),
                candidates.size());

        if (candidates.isEmpty()) {
            log.debug("No OOMKilled containers found in pod={}/{}, skipping event publish", ns, name);
            return;
        }

        candidates.forEach(cs -> {
            var key = containerKey(pod, cs);
            var currentRestartCount = cs.getRestartCount() != null ? cs.getRestartCount() : 0;
            var prev = seenRestartCounts.put(key, currentRestartCount);

            log.debug(
                    "Dedup check: container={} prevRestartCount={} currentRestartCount={}",
                    key,
                    prev,
                    currentRestartCount);

            if (prev != null && prev >= currentRestartCount) {
                log.debug(
                        "Suppressed duplicate OOMKill event: container={} restartCount={} (not advanced from prev={})",
                        key,
                        currentRestartCount,
                        prev);
                return;
            }

            log.warn(
                    "OOMKill detected: container={} pod={}/{} restartCount={} exitCode={} reason={}",
                    cs.getName(),
                    ns,
                    name,
                    currentRestartCount,
                    resolveExitCode(cs),
                    resolveReason(cs));

            var memoryKillPodEventWrapper = ContainerMemoryKillEventWrapper.builder()
                    .associatedEvent(null)
                    .failedPod(pod)
                    .build();
            log.debug("Publishing MemoryKillPodEventWrapper: container={} pod={}/{}", cs.getName(), ns, name);
            applicationEventPublisher.publishEvent(memoryKillPodEventWrapper);
            log.debug(
                    "MemoryKillPodEventWrapper published successfully for container={} pod={}/{}",
                    cs.getName(),
                    ns,
                    name);
        });
    }

    // -------------------------------------------------------------------------
    // Detection
    // -------------------------------------------------------------------------

    private List<ContainerStatus> detectOomKilledContainers(Pod pod) {
        PodStatus status = pod.getStatus();
        if (status == null) {
            log.debug(
                    "detectOomKilledContainers: null PodStatus for pod={}/{}, skipping",
                    pod.getMetadata().getNamespace(),
                    pod.getMetadata().getName());
            return List.of();
        }

        var all = new ArrayList<ContainerStatus>();
        if (status.getContainerStatuses() != null) all.addAll(status.getContainerStatuses());
        if (status.getInitContainerStatuses() != null) all.addAll(status.getInitContainerStatuses());
        if (status.getEphemeralContainerStatuses() != null) all.addAll(status.getEphemeralContainerStatuses());

        log.debug(
                "detectOomKilledContainers: pod={}/{} regular={} init={} ephemeral={}",
                pod.getMetadata().getNamespace(),
                pod.getMetadata().getName(),
                nullSafeSize(status.getContainerStatuses()),
                nullSafeSize(status.getInitContainerStatuses()),
                nullSafeSize(status.getEphemeralContainerStatuses()));

        return all.stream()
                .filter(cs -> {
                    boolean oom = isOomKilled(cs);
                    log.debug(
                            "Container check: name={} restartCount={} isOomKilled={}",
                            cs.getName(),
                            cs.getRestartCount(),
                            oom);
                    return oom;
                })
                .toList();
    }

    private boolean isOomKilled(ContainerStatus cs) {
        var fromLastState = Optional.ofNullable(cs.getLastState())
                .map(ContainerState::getTerminated)
                .orElse(null);
        var fromCurrentState = Optional.ofNullable(cs.getState())
                .map(ContainerState::getTerminated)
                .orElse(null);

        log.debug(
                "isOomKilled: container={} lastState.terminated=[reason={}, exitCode={}] state.terminated=[reason={}, exitCode={}]",
                cs.getName(),
                fromLastState != null ? fromLastState.getReason() : "null",
                fromLastState != null ? fromLastState.getExitCode() : "null",
                fromCurrentState != null ? fromCurrentState.getReason() : "null",
                fromCurrentState != null ? fromCurrentState.getExitCode() : "null");

        return isTerminatedOom(fromLastState) || isTerminatedOom(fromCurrentState);
    }

    private boolean isTerminatedOom(io.fabric8.kubernetes.api.model.ContainerStateTerminated terminated) {
        if (terminated == null) return false;
        boolean byReason = "OOMKilled".equals(terminated.getReason());
        boolean byExitCode = Objects.equals(terminated.getExitCode(), 137);
        log.debug(
                "isTerminatedOom: reason={} exitCode={} byReason={} byExitCode={}",
                terminated.getReason(),
                terminated.getExitCode(),
                byReason,
                byExitCode);
        return byReason || byExitCode;
    }

    private String containerKey(Pod pod, ContainerStatus cs) {
        return pod.getMetadata().getNamespace() + "/" + pod.getMetadata().getName() + "/" + cs.getName();
    }

    private int countAllContainers(PodStatus status) {
        if (status == null) return 0;
        return nullSafeSize(status.getContainerStatuses())
                + nullSafeSize(status.getInitContainerStatuses())
                + nullSafeSize(status.getEphemeralContainerStatuses());
    }

    @EventListener
    private int nullSafeSize(List<?> list) {
        return list != null ? list.size() : 0;
    }

    private int resolveExitCode(ContainerStatus cs) {
        return Optional.ofNullable(cs.getLastState())
                .map(ContainerState::getTerminated)
                .or(() -> Optional.ofNullable(cs.getState()).map(ContainerState::getTerminated))
                .map(t -> t.getExitCode() != null ? t.getExitCode() : 137)
                .orElse(137);
    }

    private String resolveReason(ContainerStatus cs) {
        return Optional.ofNullable(cs.getLastState())
                .map(ContainerState::getTerminated)
                .or(() -> Optional.ofNullable(cs.getState()).map(ContainerState::getTerminated))
                .filter(t -> t.getReason() != null)
                .map(ContainerStateTerminated::getReason)
                .orElse("unknown");
    }
}

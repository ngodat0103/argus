package dev.datrollout.argus.kubernetes.responder;

import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import dev.datrollout.argus.kubernetes.persistence.ContainerMemoryKubernetesIncident;
import dev.datrollout.argus.kubernetes.phase.runtime.ContainerMemoryKillEventWrapper;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

@Component
@RequiredArgsConstructor
@Slf4j
public class KubernetesEventListener {
    private final AgentPlatform agentPlatform;
    private final Scheduler scheduler;
    private static final Duration TIMEOUT_DURATION = Duration.ofMinutes(2);

    @EventListener
    public void onContainerMemoryKillEvent(ContainerMemoryKillEventWrapper containerMemoryKillEventWrapper) {

        Mono.fromRunnable(() -> {
                    log.info("Received container memory kill event {}", containerMemoryKillEventWrapper);
                    ContainerMemoryKubernetesIncident containerMemoryKubernetesIncident = AgentInvocation.builder(
                                    this.agentPlatform)
                            .build(ContainerMemoryKubernetesIncident.class)
                            .invoke(containerMemoryKillEventWrapper);
                    log.info("Container memory incident created {}", containerMemoryKubernetesIncident);
                })
                .timeout(TIMEOUT_DURATION)
                .subscribeOn(this.scheduler)
                .subscribe(null, error -> {
                    if (error instanceof TimeoutException timeoutException) {
                        log.error(
                                "Agent platform did not respond within {}: {}",
                                TIMEOUT_DURATION,
                                containerMemoryKillEventWrapper,
                                timeoutException);
                    } else {
                        log.error("Unexpected error processing OOM event", error);
                    }
                });
    }
}

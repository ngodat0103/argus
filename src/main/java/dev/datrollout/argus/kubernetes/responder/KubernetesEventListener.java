package dev.datrollout.argus.kubernetes.responder;

import com.embabel.agent.core.AgentPlatform;
import dev.datrollout.argus.kubernetes.phase.runtime.ContainerMemoryKillEvent;
import java.time.Duration;
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

    @EventListener
    public void onContainerMemoryKillEvent(ContainerMemoryKillEvent containerMemoryKillEvent) {
        Mono.fromRunnable(() -> {
                    // Todo log here, place holder here
                    log.info("Received container memory kill event {}", containerMemoryKillEvent);
                })
                .timeout(Duration.ofSeconds(10))
                .subscribeOn(this.scheduler)
                .subscribe();
    }
}

package dev.datrollout.argus.github;

import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.api.invocation.AgentInvocation;
import com.embabel.agent.core.AgentPlatform;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.core.Verbosity;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.datrollout.argus.github.event.GHPullRequestEvent;
import dev.datrollout.argus.github.event.GHPushEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

@RestController
@RequiredArgsConstructor
@Slf4j
public class GithubEventController {

    private final ObjectMapper objectMapper;
    private final AgentPlatform agentPlatform;
    private final Scheduler scheduler;
    private final GithubStatusOutputChannel githubStatusOutputChannel;

    @PostMapping("/webhook")
    public Mono<ResponseEntity<Void>> handleEvent(
            @RequestHeader("X-GitHub-Event") String event, @RequestBody byte[] rawBody) {
        Mono.fromRunnable(() -> routeEvent(event, rawBody))
                .subscribeOn(scheduler)
                .subscribe();
        return Mono.just(ResponseEntity.ok().build());
    }

    private void routeEvent(String event, byte[] rawBody) {
        try {
            switch (event) {
                case "push" -> {
                    GHPushEvent payload = objectMapper.readValue(rawBody, GHPushEvent.class);
                    log.info(
                            "push -> repo={}, ref={}, pusher={}",
                            payload.repository().full_name(),
                            payload.ref(),
                            payload.pusher().name());
                    Verbosity verbosity = Verbosity.DEFAULT.withDebug(true).withShowPlanning(true);
                    ProcessOptions processOptions = ProcessOptions.DEFAULT
                            .withEphemeral(false)
                            .withVerbosity(verbosity)
                            .withOutputChannel(githubStatusOutputChannel)
                            .withPlannerType(PlannerType.GOAP);
                    AgentInvocation<DocSyncResult> docSyncResultAgentInvocation = AgentInvocation.builder(
                                    this.agentPlatform)
                            .options(processOptions)
                            .build(DocSyncResult.class);
                    DocSyncResult docSyncResult = docSyncResultAgentInvocation.invoke(payload);
                    int stop = 0;
                }
                case "pull_request" -> {
                    GHPullRequestEvent payload = objectMapper.readValue(rawBody, GHPullRequestEvent.class);
                    log.info(
                            "pull_request -> action={}, repo={}, #{}",
                            payload.action(),
                            payload.repository().full_name(),
                            payload.number());
                    // TODO: business logic
                }
                default -> log.debug("Ignoring unsubscribed event: {}", event);
            }
        } catch (Exception e) {
            log.error("Failed to process GitHub event: {}", event, e);
        }
    }
}

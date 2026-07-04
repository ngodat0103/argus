package dev.datrollout.argus.github;

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
import reactor.core.scheduler.Schedulers;

@RestController
@RequiredArgsConstructor
@Slf4j
public class GithubEventController {

    private final ObjectMapper objectMapper;

    @PostMapping("/webhook")
    public Mono<ResponseEntity<Void>> handleEvent(
            @RequestHeader("X-GitHub-Event") String event, @RequestBody byte[] rawBody) {
        return Mono.fromRunnable(() -> routeEvent(event, rawBody))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.ok().build()));
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
                    // TODO: business logic
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

package dev.datrollout.argus.github.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.datrollout.argus.github.event.shared.GHRepository;
import dev.datrollout.argus.github.event.shared.GHSender;
import java.util.List;

public record GHPushEvent(
        String ref,
        String before,
        String after,
        GHRepository repository,
        GHPusher pusher,
        GHSender sender,
        List<GHCommit> commits,
        GHCommit head_commit) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GHPusher(String name, String email) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GHCommit(String id, String message, String timestamp, GHAuthor author) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record GHAuthor(String name, String email) {}
    }
}

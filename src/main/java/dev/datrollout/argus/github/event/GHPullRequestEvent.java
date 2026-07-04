package dev.datrollout.argus.github.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.datrollout.argus.github.event.shared.GHRepository;
import dev.datrollout.argus.github.event.shared.GHSender;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GHPullRequestEvent(
        String action, // opened, closed, synchronize, reopened, edited, etc.
        int number,
        GHPullRequest pull_request,
        GHRepository repository,
        GHSender sender) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GHPullRequest(
            long id,
            int number,
            String title,
            String state, // open, closed
            boolean merged,
            GHBranch head,
            GHBranch base,
            GHUser user) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GHBranch(String ref, String sha) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GHUser(String login, long id) {}
}

package dev.datrollout.argus.github.embabel;

import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.api.reference.LlmReference;
import com.embabel.agent.api.tool.Tool;
import dev.datrollout.argus.github.event.GHPushEvent;
import java.io.IOException;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

/**
 * LLM-callable GitHub operations for the docs-sync flow (currently: opening a pull request).
 *
 * <p>Exposed to the LLM as an {@link LlmReference}: {@link #tools()} publishes the {@link LlmTool}
 * methods (namespaced under the {@code github} prefix) and {@link #notes()} renders the usage
 * guidance into the system prompt. When a pull request is opened, its html URL and number are
 * recorded on this instance so {@code DocsAgent} can read the outcome deterministically after the
 * LLM run (rather than parsing the model's free text).
 */
@Slf4j
@Builder
public class GithubLlmReference implements LlmReference {

    private final GitHub gitHub;
    private final String temporaryToken;
    private final GHRepository ghRepository;
    private final GHPushEvent ghPushEvent;

    /** Html URL of the pull request opened during this run, or null if none was opened. */
    @Getter
    private String createdPullRequestUrl;

    /** Number of the pull request opened during this run, or null if none was opened. */
    @Getter
    private Integer createdPullRequestNumber;

    @LlmTool(description = """
                    Return the repository's default branch name. Use it as the base branch when
                    opening a pull request.
                    """)
    public String defaultBranch() {
        return ghRepository.getDefaultBranch();
    }

    @LlmTool(description = """
                    Open a pull request from an already-pushed head branch into the repository's
                    default branch, describing the documentation update. Push the branch with the git
                    tools before calling this. Returns the created pull request URL.
                    """)
    public String createPullRequest(
            @LlmTool.Param(description = "the pull request title") String title,
            @LlmTool.Param(description = "the pull request body / description (markdown)") String body,
            @LlmTool.Param(description = "the head branch that was pushed") String headBranch) {
        try {
            GHPullRequest pullRequest =
                    ghRepository.createPullRequest(title, headBranch, ghRepository.getDefaultBranch(), body);
            this.createdPullRequestUrl = pullRequest.getHtmlUrl().toString();
            this.createdPullRequestNumber = pullRequest.getNumber();
            return "opened pull request #" + this.createdPullRequestNumber + " -> " + this.createdPullRequestUrl;
        } catch (IOException e) {
            log.error("Failed to open pull request from {}", headBranch, e);
            return "ERROR: could not open pull request: " + e.getMessage();
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // LlmReference: identity, usage notes (rendered into the system prompt) and tools
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public @NotNull String getName() {
        return "github";
    }

    @Override
    public @NotNull String getDescription() {
        return "GitHub operations bound to THIS repository, already authenticated: read the default "
                + "branch and open a pull request with the proposed documentation changes.";
    }

    @Override
    public @NotNull String notes() {
        return """
                These tools are already bound to this repository with authentication configured for
                you. They are NOT general-purpose GitHub commands: you never provide a repository
                name/owner, access token or API URL - only the arguments each tool asks for.

                Use them once your documentation edits are committed and pushed with the git
                reference. Tool names are prefixed with 'github_'.
                  - defaultBranch     - discover the base branch to target.
                  - createPullRequest - open the PR from your pushed branch into the default branch;
                                        pass a concise title, a markdown body explaining what changed
                                        and why, and the head branch you pushed.
                Open at most one pull request per run, and only when documentation actually needs
                updating. A result starting with 'ERROR:' means the call failed.
                """;
    }

    @Override
    public @NotNull List<Tool> tools() {
        return Tool.fromInstance(this);
    }
}

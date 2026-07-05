package dev.datrollout.argus.github;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.api.common.StuckHandler;
import com.embabel.agent.api.common.StuckHandlerResult;
import com.embabel.agent.core.AgentProcess;
import com.embabel.chat.AssistantMessage;
import dev.datrollout.argus.github.client.GitHubAppClientRegistry;
import dev.datrollout.argus.github.client.GitHubAppJwt;
import dev.datrollout.argus.github.client.GitHubAppProperties;
import dev.datrollout.argus.github.event.GHPushEvent;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.jetbrains.annotations.NotNull;
import org.kohsuke.github.GHCompare;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;

@Agent(description = "A agent to keep docs in sync compared to the source")
@RequiredArgsConstructor
public class DocsAgent implements StuckHandler {
    private final GitHubAppClientRegistry gitHubAppClientRegistry;
    private static final String TEMP_DIR = "/tmp/argus/syncDocs";
    private final GitHubAppJwt gitHubAppJwt;
    private final GitHubAppProperties gitHubAppProperties;

    @Action
    public GitHub extractGithub(GHPushEvent ghPushEvent) {
        return this.gitHubAppClientRegistry.clientFor(
                ghPushEvent.repository().owner().login());
    }

    @Action
    public GHRepository produceGHRepository(GitHub gitHub, GHPushEvent ghPushEvent, ActionContext actionContext)
            throws IOException {
        actionContext.updateProgress("repository fetched");
        return gitHub.getRepository(ghPushEvent.repository().full_name());
    }

    @Action
    GHCompare produceGHCompare(GHPushEvent ghPushEvent, GHRepository ghRepository, ActionContext actionContext)
            throws IOException {
        String before = ghPushEvent.before();
        String after = ghPushEvent.after();
        GHCompare ghCompare = ghRepository.getCompare(before, after);
        actionContext.updateProgress("git compare fetched");
        return ghCompare;
    }

    @Action
    @AchievesGoal(description = "A Sync result with PR to update the repository")
    public DocSyncResult reactToPushEvent(
            GitHub gitHub, ActionContext actionContext, OperationContext operationContext, GHCompare ghCompare)
            throws IOException, GitAPIException {
        actionContext.updateProgress("start sync");
        DocSyncResult docSyncResult = new DocSyncResult(false, " test");
        AssistantMessage assistantMessage = new AssistantMessage("Analyze finished");
        actionContext.sendMessage(assistantMessage);
        return docSyncResult;
    }

    @Override
    public @NotNull StuckHandlerResult handleStuck(@NotNull AgentProcess agentProcess) {
        return null;
    }
}

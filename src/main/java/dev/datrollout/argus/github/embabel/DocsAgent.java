package dev.datrollout.argus.github.embabel;

import com.embabel.agent.api.annotation.AchievesGoal;
import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.Agent;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.agent.tools.file.FileTools;
import com.embabel.chat.AssistantMessage;
import dev.datrollout.argus.github.client.GitHubAppClientRegistry;
import dev.datrollout.argus.github.event.GHPushEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.kohsuke.github.GHCompare;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.springframework.ai.chat.prompt.PromptTemplate;

@Agent(description = "A agent to keep docs in sync compared to the source")
@RequiredArgsConstructor
public class DocsAgent {
    private final GitHubAppClientRegistry gitHubAppClientRegistry;
    private final DocSyncResultRepository docSyncResultRepository;

    /**
     * First time Argus sees a repository: read the whole tree and build/sync README.md from scratch.
     */
    private static final PromptTemplate FULL_SYNC_PROMPT = new PromptTemplate("""
            You are Argus, a documentation agent. This is the FIRST time you process the repository
            {repository}, so treat its documentation as uninitialised.

            Your task:
            1. Recursively explore the repository working tree with the file tools (list directories,
               read the source) until you understand what the project does, how it is structured and
               how it is built and run.
            2. Write a comprehensive README.md at the repository root that accurately documents the
               project (purpose, high-level architecture, setup, usage). Create it if it is missing,
               or bring it fully in sync if it already exists.
            3. If you changed anything, publish it:
               - create a new branch named "argus/docs-sync-{after}",
               - stage all changes, then commit with a clear message,
               - push the branch,
               - open a pull request into the default branch describing the documentation you produced.
            4. If the documentation is already complete and accurate, make no changes and open no
               pull request.

            Finish with a single short sentence summarising what you did.
            """);

    /**
     * Subsequent pushes: focus only on the files that changed in this push and update affected docs.
     */
    private static final PromptTemplate INCREMENTAL_SYNC_PROMPT = new PromptTemplate("""
            You are Argus, a documentation agent for the repository {repository}.

            A push updated {ref} from {before} to {after}. The source files changed in this push are:
            {changed_files}

            Your task:
            1. Focus ONLY on these changed files. Read them and the documentation that describes them
               (README.md and files under docs/) with the file tools.
            2. Decide whether that documentation is now out of date with respect to these changes.
            3. If it is out of date, update only the affected documentation files, then:
               - create a new branch named "argus/docs-sync-{after}",
               - stage all changes, then commit with a clear message,
               - push the branch,
               - open a pull request into the default branch describing what you updated and why.
            4. If the documentation is already in sync with these changes, make no edits and open no
               pull request.

            Finish with a single short sentence summarising your conclusion.
            """);

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
            GHPushEvent ghPushEvent,
            GHRepository ghRepository,
            GitHub gitHub,
            ActionContext actionContext,
            OperationContext operationContext,
            GHCompare ghCompare)
            throws IOException, GitAPIException {
        actionContext.updateProgress("start sync");
        String repositoryFullName = ghPushEvent.repository().full_name();

        // A merge commit (2+ parents) is almost always the result of merging a pull request -
        // including Argus's own docs-sync PRs. Skip it before doing any work to avoid reacting to
        // our own merges (and needless churn), returning a result flagged ignoreMergeCommit=true.
        if (isMergeCommit(ghRepository, ghPushEvent.after())) {
            actionContext.updateProgress("ignoring merge commit");
            DocSyncResult ignored = docSyncResultRepository.save(DocSyncResult.builder()
                    .ignoreMergeCommit(true)
                    .isOutOfSync(false)
                    .repositoryFullName(repositoryFullName)
                    .ref(ghPushEvent.ref())
                    .beforeSha(ghPushEvent.before())
                    .afterSha(ghPushEvent.after())
                    .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build());
            actionContext.sendMessage(new AssistantMessage("Skipped merge commit"));
            return ignored;
        }

        String prefix = "argus-sync-" + repositoryFullName.replace("/", "-");
        Path tempDir = Files.createTempDirectory(prefix);
        // Installation access token (NOT the app JWT): this is what git HTTPS auth accepts, used
        // as the password with the fixed username "x-access-token".
        String installationToken = this.gitHubAppClientRegistry.installationTokenFor(
                ghPushEvent.repository().owner().login());
        UsernamePasswordCredentialsProvider credentialsProvider =
                new UsernamePasswordCredentialsProvider("x-access-token", installationToken);

        // Has Argus run a real sync for this repo before? Drives full initial sync vs incremental.
        boolean initialised =
                docSyncResultRepository.existsByRepositoryFullNameAndIgnoreMergeCommitFalse(repositoryFullName);

        try (Git git = Git.cloneRepository()
                .setURI(ghRepository.getHttpTransportUrl())
                .setDirectory(tempDir.toFile())
                .setDepth(2)
                .setCredentialsProvider(credentialsProvider)
                .setTimeout(30)
                .call()) {
            FileTools fileTools = FileTools.readWrite(tempDir.toFile().getAbsolutePath());
            GitOperationLlmReference gitOperationLlmReference = new GitOperationLlmReference(git, credentialsProvider);
            GithubLlmReference githubLlmReference = GithubLlmReference.builder()
                    .gitHub(gitHub)
                    .temporaryToken(installationToken)
                    .ghPushEvent(ghPushEvent)
                    .ghRepository(ghRepository)
                    .build();

            String rendered = initialised
                    ? INCREMENTAL_SYNC_PROMPT.render(Map.of(
                            "repository", repositoryFullName,
                            "ref", ghPushEvent.ref(),
                            "before", ghPushEvent.before(),
                            "after", ghPushEvent.after(),
                            "changed_files", formatChangedFiles(ghCompare)))
                    : FULL_SYNC_PROMPT.render(Map.of("repository", repositoryFullName, "after", ghPushEvent.after()));

            actionContext.updateProgress(initialised ? "analyzing changes" : "building initial docs");

            String summary = operationContext
                    .ai()
                    .withLlmByRole("reasoning")
                    .withToolObject(fileTools)
                    .withReference(githubLlmReference)
                    .withReference(gitOperationLlmReference)
                    .generateText(rendered);

            // The github tool records the PR it opened; a non-null URL means docs were out of sync.
            String pullRequestUrl = githubLlmReference.getCreatedPullRequestUrl();
            boolean outOfSync = pullRequestUrl != null;

            DocSyncResult docSyncResult = docSyncResultRepository.save(DocSyncResult.builder()
                    .isOutOfSync(outOfSync)
                    .proposedSyncDocs(summary)
                    .repositoryFullName(repositoryFullName)
                    .ref(ghPushEvent.ref())
                    .beforeSha(ghPushEvent.before())
                    .afterSha(ghPushEvent.after())
                    .pullRequestUrl(pullRequestUrl)
                    .createdAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build());

            AssistantMessage assistantMessage =
                    new AssistantMessage(outOfSync ? "Opened PR to sync docs" : "Docs in sync");
            actionContext.sendMessage(assistantMessage);
            return docSyncResult;
        }
    }

    /** A merge commit has more than one parent. */
    private boolean isMergeCommit(GHRepository ghRepository, String sha) throws IOException {
        return ghRepository.getCommit(sha).getParentSHA1s().size() > 1;
    }

    private String formatChangedFiles(GHCompare ghCompare) {
        StringBuilder sb = new StringBuilder();
        for (var file : ghCompare.getFiles()) {
            sb.append("  - ")
                    .append(file.getStatus())
                    .append(' ')
                    .append(file.getFileName())
                    .append('\n');
        }
        return sb.isEmpty() ? "  (no files reported)\n" : sb.toString();
    }
}

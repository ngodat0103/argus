package dev.datrollout.argus.github.embabel;

import com.embabel.agent.api.annotation.LlmTool;
import com.embabel.agent.api.reference.LlmReference;
import com.embabel.agent.api.tool.Tool;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.jetbrains.annotations.NotNull;

/**
 * LLM-callable git operations on the locally cloned repository (branch, stage, commit, push).
 *
 * <p>Exposed to the LLM as an {@link LlmReference}: {@link #tools()} publishes the {@link LlmTool}
 * methods (namespaced under the {@code git} prefix) and {@link #notes()} renders the usage workflow
 * into the system prompt. Each tool returns a plain string and reports failures as
 * {@code ERROR: ...} rather than throwing, so the model can recover.
 */
@Slf4j
@RequiredArgsConstructor
public class GitOperationLlmReference implements LlmReference {

    private static final String BOT_NAME = "argus[bot]";
    private static final String BOT_EMAIL = "argus[bot]@users.noreply.github.com";

    private final Git git;
    private final CredentialsProvider credentialsProvider;

    // ──────────────────────────────────────────────────────────────────────────
    // LlmReference: identity, usage notes (rendered into the system prompt) and tools
    // ──────────────────────────────────────────────────────────────────────────

    @Override
    public @NotNull String getName() {
        return "git";
    }

    @Override
    public @NotNull String getDescription() {
        return "Git operations bound to THIS repository's already-cloned local checkout: inspect "
                + "status, create a branch, stage, commit and push documentation changes.";
    }

    @Override
    public @NotNull String notes() {
        return """
                These tools already operate on this repository's local clone, with the remote and
                push credentials pre-configured for you. They are NOT general-purpose git commands:
                you never provide a repository URL, clone path, remote name or authentication -
                only the arguments each tool asks for (a branch name, a commit message).

                Use them to publish documentation edits you made with the file tools. Tool names are
                prefixed with 'git_'. Typical workflow, in order:
                  1. status       - confirm which files you changed.
                  2. createBranch - start a branch named argus/docs-sync-<sha>.
                  3. stageAll     - stage every change.
                  4. commit       - commit with a clear message.
                  5. push         - push the branch to origin (already authenticated).
                Then open a pull request with the github reference.
                Every tool returns a plain string; a result starting with 'ERROR:' means the
                operation failed and you should stop and report it.
                """;
    }

    @Override
    public @NotNull List<Tool> tools() {
        return Tool.fromInstance(this);
    }

    @LlmTool(description = """
                    Show the current working-tree status of the cloned repository: which files are
                    added, modified, removed or untracked relative to HEAD. Use this to confirm which
                    documentation files you have edited before committing.
                    """)
    public String status() {
        try {
            Status status = git.status().call();
            StringBuilder sb = new StringBuilder("=== git status ===\n");
            appendGroup(sb, "added", status.getAdded());
            appendGroup(sb, "changed", status.getChanged());
            appendGroup(sb, "modified", status.getModified());
            appendGroup(sb, "removed", status.getRemoved());
            appendGroup(sb, "missing", status.getMissing());
            appendGroup(sb, "untracked", status.getUntracked());
            if (status.isClean()) {
                sb.append("(working tree clean)\n");
            }
            return sb.toString();
        } catch (GitAPIException e) {
            log.error("git status failed", e);
            return "ERROR: could not read git status: " + e.getMessage();
        }
    }

    @LlmTool(description = """
                    Create and check out a new branch from the current HEAD. Call this before staging
                    and committing documentation changes so the work lands on its own branch.
                    """)
    public String createBranch(
            @LlmTool.Param(description = "the new branch name, e.g. argus/docs-sync-<sha>") String name) {
        try {
            git.checkout().setCreateBranch(true).setName(name).call();
            return "created and checked out branch " + name;
        } catch (GitAPIException e) {
            log.error("git create branch failed for {}", name, e);
            return "ERROR: could not create branch " + name + ": " + e.getMessage();
        }
    }

    @LlmTool(description = """
                    Stage all changes in the working tree (new, modified and deleted files) so they can
                    be committed. Call this after editing documentation files.
                    """)
    public String stageAll() {
        try {
            git.add().addFilepattern(".").setUpdate(false).call();
            // addFilepattern with setUpdate(false) stages new + modified but not deletions; a second
            // pass with setUpdate(true) stages deletions too.
            git.add().addFilepattern(".").setUpdate(true).call();
            return "staged all changes";
        } catch (GitAPIException e) {
            log.error("git add failed", e);
            return "ERROR: could not stage changes: " + e.getMessage();
        }
    }

    @LlmTool(description = """
                    Commit the currently staged changes with the given message, authored by the Argus
                    bot. Stage changes with stageAll first.
                    """)
    public String commit(@LlmTool.Param(description = "the commit message") String message) {
        try {
            var rev = git.commit()
                    .setMessage(message)
                    .setAuthor(BOT_NAME, BOT_EMAIL)
                    .setCommitter(BOT_NAME, BOT_EMAIL)
                    .setSign(false) // ignore any commit.gpgsign / gpg.format config; JGit can't sign here
                    .call();
            return "committed " + rev.getName();
        } catch (GitAPIException e) {
            log.error("git commit failed", e);
            return "ERROR: could not commit: " + e.getMessage();
        }
    }

    @LlmTool(description = """
                    Push the given local branch to the remote (origin) using the Argus credentials.
                    Call this after committing, and before opening a pull request.
                    """)
    public String push(@LlmTool.Param(description = "the local branch name to push") String branch) {
        try {
            Iterable<PushResult> results = git.push()
                    .setCredentialsProvider(credentialsProvider)
                    .setRefSpecs(new RefSpec(branch + ":" + branch))
                    .call();
            StringBuilder sb = new StringBuilder("pushed " + branch + ":\n");
            for (PushResult result : results) {
                result.getRemoteUpdates().forEach(u -> sb.append("  ")
                        .append(u.getRemoteName())
                        .append(" -> ")
                        .append(u.getStatus())
                        .append('\n'));
            }
            return sb.toString();
        } catch (GitAPIException e) {
            log.error("git push failed for branch {}", branch, e);
            return "ERROR: could not push branch " + branch + ": " + e.getMessage();
        }
    }

    private void appendGroup(StringBuilder sb, String label, Set<String> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        sb.append(label).append(":\n");
        new TreeSet<>(files).forEach(f -> sb.append("  ").append(f).append('\n'));
    }
}

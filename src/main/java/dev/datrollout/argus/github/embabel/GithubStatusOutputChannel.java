package dev.datrollout.argus.github.embabel;

import com.embabel.agent.api.channel.MessageOutputChannelEvent;
import com.embabel.agent.api.channel.OutputChannel;
import com.embabel.agent.api.channel.OutputChannelEvent;
import com.embabel.agent.api.channel.ProgressOutputChannelEvent;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Blackboard;
import com.embabel.chat.AssistantMessage;
import dev.datrollout.argus.github.event.GHPushEvent;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.kohsuke.github.GHCommitState;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class GithubStatusOutputChannel implements OutputChannel {
    private static final String CONTEXT = "[Dev] Argus/keep-docs-sync";

    @Override
    public void send(@NotNull OutputChannelEvent outputChannelEvent) {
        AgentProcess agentProcess = AgentProcess.get();
        assert agentProcess != null;
        Blackboard blackboard = agentProcess.getBlackboard();
        GitHub gitHub = blackboard.last(GitHub.class);
        GHRepository ghRepository = blackboard.last(GHRepository.class);
        GHPushEvent ghPushEvent = blackboard.last(GHPushEvent.class);
        if (outputChannelEvent instanceof ProgressOutputChannelEvent progressOutputChannelEvent) {
            if (gitHub != null && ghRepository != null && ghPushEvent != null) {
                try {
                    ghRepository.createCommitStatus(
                            ghPushEvent.after(),
                            GHCommitState.PENDING,
                            "https://argus.datrollout.dev",
                            progressOutputChannelEvent.getMessage(),
                            CONTEXT);
                } catch (IOException e) {
                    log.error("Error when send commitStatus update", e);
                    return;
                }
                return;
            }
        } else if (outputChannelEvent instanceof MessageOutputChannelEvent messageOutputChannelEvent) {
            if (messageOutputChannelEvent.getMessage() instanceof AssistantMessage assistantMessage) {
                if (gitHub != null && ghRepository != null && ghPushEvent != null) {
                    try {
                        ghRepository.createCommitStatus(
                                ghPushEvent.after(),
                                GHCommitState.SUCCESS,
                                "https://argus.datrollout.dev",
                                assistantMessage.getTextContent(),
                                CONTEXT);
                    } catch (IOException e) {
                        log.error("Error when send commitStatus update", e);
                        return;
                    }
                }
            }
        }

        log.warn("Ignore action didn't produce enough require objects yet");
    }
}

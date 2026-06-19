package dev.datrollout.argus.embabel;

import static dev.datrollout.argus.embabel.PromptRegistry.coStar;

import com.embabel.agent.api.annotation.Action;
import com.embabel.agent.api.annotation.EmbabelComponent;
import com.embabel.agent.api.annotation.Provided;
import com.embabel.agent.api.common.ActionContext;
import com.embabel.agent.api.common.OperationContext;
import com.embabel.chat.Conversation;
import com.embabel.chat.UserMessage;
import dev.datrollout.argus.incidentManipulation.KubernetesEventResponderChain;
import dev.datrollout.argus.incidentManipulation.event.KubernetesEvent;
import dev.datrollout.argus.kubernetes.embabel.ConfigMapUnfoldingTool;
import dev.datrollout.argus.kubernetes.embabel.EventsUnfoldingTool;
import dev.datrollout.argus.kubernetes.embabel.KubernetesResourceUnfoldingTool;
import dev.datrollout.argus.kubernetes.embabel.LogsUnfoldingTool;
import dev.datrollout.argus.kubernetes.embabel.NetworkingDebuggingUnfoldingTool;
import dev.datrollout.argus.kubernetes.embabel.SchedulingDiagnosticsUnfoldingTool;
import dev.datrollout.argus.kubernetes.embabel.SecretUnfoldingTool;
import dev.datrollout.argus.kubernetes.embabel.WorkloadStateUnfoldingTool;
import dev.datrollout.argus.telegram.TelegramOutputChannel;
import dev.datrollout.argus.telegram.TelegramUserReference;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@EmbabelComponent
@RequiredArgsConstructor
@Slf4j
public class Orchestrator {

    private final KubernetesResourceUnfoldingTool kubernetesResourceUnfoldingTool;
    private final NetworkingDebuggingUnfoldingTool networkingDebuggingUnfoldingTool;
    private final LogsUnfoldingTool logsUnfoldingTool;
    private final EventsUnfoldingTool eventsUnfoldingTool;
    private final WorkloadStateUnfoldingTool workloadStateUnfoldingTool;
    private final SchedulingDiagnosticsUnfoldingTool schedulingDiagnosticsUnfoldingTool;
    private final ConfigMapUnfoldingTool configMapUnfoldingTool;
    private final SecretUnfoldingTool secretUnfoldingTool;

    @Action(trigger = UserMessage.class, clearBlackboard = true)
    public void defaultChat(
            Conversation conversation,
            @Provided TelegramClient telegramClient,
            OperationContext operationContext,
            ActionContext actionContext) {
        TelegramOutputChannel telegramOutputChannel =
                (TelegramOutputChannel) operationContext.getProcessContext().getOutputChannel();
        TelegramUserReference telegramuserReference =
                new TelegramUserReference(telegramOutputChannel.getUserMessage(), telegramClient);
        var assistantMessage = operationContext
                .ai()
                .withLlmByRole("reasoning")
                .withPromptContributor(coStar)
                .withReference(telegramuserReference)
                .withTool(kubernetesResourceUnfoldingTool)
                .withTool(networkingDebuggingUnfoldingTool)
                .withTool(logsUnfoldingTool)
                .withTool(eventsUnfoldingTool)
                .withTool(workloadStateUnfoldingTool)
                .withTool(schedulingDiagnosticsUnfoldingTool)
                .withTool(configMapUnfoldingTool)
                .withTool(secretUnfoldingTool)
                .respond(conversation.getMessages());
        actionContext.sendAndSave(Objects.requireNonNull(assistantMessage));
    }

    @Action(trigger = KubernetesEvent.class, clearBlackboard = true)
    public void onKubernetesIncident(
            @Provided KubernetesEventResponderChain kubernetesEventResponderChain,
            ActionContext actionContext,
            KubernetesEvent kubernetesEvent,
            OperationContext operationContext) {
        kubernetesEventResponderChain.delegate(kubernetesEvent, operationContext, actionContext);
    }
}

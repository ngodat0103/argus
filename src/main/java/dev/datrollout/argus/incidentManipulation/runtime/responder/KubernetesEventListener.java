package dev.datrollout.argus.incidentManipulation.runtime.responder;

import com.embabel.chat.Chatbot;
import dev.datrollout.argus.incidentManipulation.event.ContainerMemoryKillKubernetesEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Component
@RequiredArgsConstructor
@Slf4j
public class KubernetesEventListener {
    private final Chatbot chatbot;
    private final TelegramClient telegramClient;

    @EventListener
    public void onContainerMemoryKillEvent(ContainerMemoryKillKubernetesEvent containerMemoryKillEventWrapper) {}
}

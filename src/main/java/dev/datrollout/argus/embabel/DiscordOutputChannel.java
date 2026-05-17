package dev.datrollout.argus.embabel;

import com.embabel.agent.api.channel.MessageOutputChannelEvent;
import com.embabel.agent.api.channel.OutputChannel;
import com.embabel.agent.api.channel.OutputChannelEvent;
import com.embabel.agent.api.channel.ProgressOutputChannelEvent;
import com.embabel.chat.AssistantMessage;
import discord4j.core.DiscordClient;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;

@Slf4j
public class DiscordOutputChannel implements OutputChannel {
    @Override
    public void send(@NonNull OutputChannelEvent outputChannelEvent) {

        if(outputChannelEvent instanceof ProgressOutputChannelEvent progressOutputChannelEvent) {
            log.info("send progress here, should be ephemeral");

        }
        else if(outputChannelEvent instanceof MessageOutputChannelEvent messageOutputChannelEvent){
            if(messageOutputChannelEvent.getMessage() instanceof AssistantMessage) {
               log.info("Sending message to discord: {}", messageOutputChannelEvent.getMessage());
            }
        }

    }
}

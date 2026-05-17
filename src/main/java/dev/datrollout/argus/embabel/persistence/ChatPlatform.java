package dev.datrollout.argus.embabel.persistence;

public enum ChatPlatform {
    DISCORD;

    public static ChatPlatform fromConversationId(String conversationId) {
        if (conversationId.contains("discord")) {
            return DISCORD;
        }
        throw new IllegalArgumentException(
                "Cannot detect chat platform from conversationId: " + conversationId + ", meaning code bug");
    }
}

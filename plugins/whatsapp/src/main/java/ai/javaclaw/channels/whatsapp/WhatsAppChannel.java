package ai.javaclaw.channels.whatsapp;

import ai.javaclaw.agent.Agent;
import ai.javaclaw.channels.Channel;
import ai.javaclaw.channels.ChannelMessageReceivedEvent;
import ai.javaclaw.channels.ChannelRegistry;
import ai.javaclaw.llm.ModelErrors;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Puts the agent behind {@link WhatsApp}: it answers what arrives there, and it is where replies
 * to background tasks are delivered.
 */
public class WhatsAppChannel implements Channel {

    static final String CHANNEL_ID = "whatsapp";

    private static final Logger LOGGER = LoggerFactory.getLogger(WhatsAppChannel.class);

    private final WhatsApp whatsApp;
    private final ChannelRegistry channelRegistry;
    private final Agent agent;

    public WhatsAppChannel(WhatsApp whatsApp, ChannelRegistry channelRegistry, Agent agent) {
        this.whatsApp = whatsApp;
        this.channelRegistry = channelRegistry;
        this.agent = agent;
    }

    @Override
    public String getName() {
        return CHANNEL_ID;
    }

    @PostConstruct
    public void start() {
        if (!whatsApp.start()) {
            return;
        }
        whatsApp.registerMessageReceiver(this::onMessage);
        channelRegistry.registerChannel(this);
        LOGGER.info("Started WhatsApp integration via wacli");
    }

    @PreDestroy
    public void stop() {
        whatsApp.stop();
        channelRegistry.unregisterChannel(this);
    }

    @Override
    public void sendMessage(String message) {
        whatsApp.sendMessage(message);
    }

    void onMessage(String fromJidId, String message) {
        channelRegistry.publishMessageReceivedEvent(new WhatsAppChannelMessageReceivedEvent(getName(), message, fromJidId));
        try {
            whatsApp.sendMessage(agent.respondTo(fromJidId, message));
        } catch (RuntimeException e) {
            LOGGER.error("Failed to handle WhatsApp message for chat '{}'", fromJidId, e);
            // Without this the user only sees silence.
            try {
                whatsApp.sendMessage(failureReply(e));
            } catch (RuntimeException sendFailure) {
                LOGGER.error("Could not tell WhatsApp chat '{}' about the failure", fromJidId, sendFailure);
            }
        }
    }

    static final String QUOTA_EXCEEDED_REPLY = "⚠️ The AI model's usage quota is used up, so I can't answer right now. Please try again later.";
    static final String GENERIC_FAILURE_REPLY = "⚠️ Something went wrong while answering your message. Please try again in a moment.";

    static String failureReply(Throwable failure) {
        return ModelErrors.isQuotaExceeded(failure) ? QUOTA_EXCEEDED_REPLY : GENERIC_FAILURE_REPLY;
    }

    static class WhatsAppChannelMessageReceivedEvent extends ChannelMessageReceivedEvent {

        private final String conversationId;

        public WhatsAppChannelMessageReceivedEvent(String channel, String message, String conversationId) {
            super(channel, message);
            this.conversationId = conversationId;
        }

        public String getConversationId() {
            return conversationId;
        }
    }
}

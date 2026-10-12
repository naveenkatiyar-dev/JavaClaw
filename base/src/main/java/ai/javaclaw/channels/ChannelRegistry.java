package ai.javaclaw.channels;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class ChannelRegistry {

    private final Map<String, Channel> channels;
    private final AtomicReference<ChannelMessageReceivedEvent> lastChannelMessage;
    private String defaultChannelName;
    private String notificationChannelName;

    public ChannelRegistry() {
        this.channels = new HashMap<>();
        this.lastChannelMessage = new AtomicReference<>();
    }

    public void registerChannel(Channel channel) {
        channels.put(channel.getName(), channel);
        if (channels.size() == 1) {
            this.defaultChannelName = channel.getName();
        }
    }

    public void unregisterChannel(Channel channel) {
        channels.remove(channel.getName());
    }

    public Channel getLatestChannel() {
        if (lastChannelMessage.get() != null) {
            return channels.get(lastChannelMessage.get().getChannel());
        }
        return channels.get(defaultChannelName);
    }

    /**
     * Where background-task results go: the channel named by {@code agent.tasks.notification-channel}
     * while it is registered, otherwise the channel the user wrote in last. A pull-only channel like
     * the web chat is a poor place for a reminder, so a push channel can be pinned here.
     */
    public Channel getNotificationChannel() {
        if (notificationChannelName != null && !notificationChannelName.isBlank()) {
            Channel preferred = channels.get(notificationChannelName);
            if (preferred != null) {
                return preferred;
            }
        }
        return getLatestChannel();
    }

    @Value("${agent.tasks.notification-channel:}")
    public void setNotificationChannelName(String notificationChannelName) {
        this.notificationChannelName = notificationChannelName;
    }

    public void publishMessageReceivedEvent(ChannelMessageReceivedEvent event) {
        lastChannelMessage.set(event);
    }
}

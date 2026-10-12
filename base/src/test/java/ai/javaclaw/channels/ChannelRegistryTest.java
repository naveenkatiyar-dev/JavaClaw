package ai.javaclaw.channels;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelRegistryTest {

    private final ChannelRegistry registry = new ChannelRegistry();
    private final Channel web = channel("web");
    private final Channel whatsapp = channel("whatsapp");

    @Test
    void notifiesTheLatestChannelWhenNoneIsPinned() {
        registry.registerChannel(whatsapp);
        registry.registerChannel(web);
        registry.publishMessageReceivedEvent(new ChannelMessageReceivedEvent("web", "remind me"));

        assertThat(registry.getNotificationChannel()).isSameAs(web);
    }

    @Test
    void notifiesThePinnedChannelEvenWhenTheUserWroteElsewhere() {
        registry.setNotificationChannelName("whatsapp");
        registry.registerChannel(web);
        registry.registerChannel(whatsapp);
        registry.publishMessageReceivedEvent(new ChannelMessageReceivedEvent("web", "remind me"));

        assertThat(registry.getNotificationChannel()).isSameAs(whatsapp);
    }

    @Test
    void fallsBackToTheLatestChannelWhileThePinnedOneIsNotRegistered() {
        registry.setNotificationChannelName("whatsapp");
        registry.registerChannel(web);
        registry.publishMessageReceivedEvent(new ChannelMessageReceivedEvent("web", "remind me"));

        assertThat(registry.getNotificationChannel()).isSameAs(web);
    }

    private static Channel channel(String name) {
        return new Channel() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public void sendMessage(String message) {
            }
        };
    }
}

package ai.javaclaw.tools.whatsapp;

import ai.javaclaw.channels.whatsapp.WhatsApp;
import ai.javaclaw.channels.whatsapp.WhatsApp.Message;
import ai.javaclaw.channels.whatsapp.WhatsApp.UnreadGroup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WhatsAppGroupDigestToolTest {

    private final WhatsApp whatsApp = mock(WhatsApp.class);
    private final List<Prompt> prompts = new ArrayList<>();
    private final ChatClient model = ChatClient.builder(prompt -> {
        prompts.add(prompt);
        return new ChatResponse(List.of(new Generation(new AssistantMessage("- plans for dinner"))));
    }).build();

    private static final List<Message> MESSAGES = List.of(
            new Message(Instant.parse("2026-10-09T16:01:00Z"), "", true, "Hi all"),
            new Message(Instant.parse("2026-10-09T16:04:16Z"), "Bob", false, "Dinner at 8?"));

    @BeforeEach
    void everyGroupIsLargeUnlessATestSaysOtherwise() {
        lenient().when(whatsApp.participantCount(anyString())).thenReturn(OptionalInt.of(50));
    }

    @Test
    void summarisesUnmutedGroupsWithOneModelCallPerGroup() {
        when(whatsApp.unreadGroups()).thenReturn(List.of(
                new UnreadGroup("222@g.us", "Society", 300, true),
                new UnreadGroup("333@g.us", "Office", 42, false),
                new UnreadGroup("111@g.us", "Family", 5, false)));
        when(whatsApp.latestMessages(anyString(), anyInt())).thenReturn(MESSAGES);

        String digest = new WhatsAppGroupDigestTool(whatsApp, () -> model).summarizeUnreadWhatsAppGroups(null, null);

        assertThat(digest)
                .contains("## Office (42 unread)\n- plans for dinner")
                .contains("## Family (5 unread)")
                .doesNotContain("Society");
        assertThat(prompts).hasSize(2);
        assertThat(prompts.getFirst().getContents()).contains("Me: Hi all").contains("Bob: Dinner at 8?");
        verify(whatsApp).latestMessages("333@g.us", WhatsAppGroupDigestTool.MAX_MESSAGES_PER_GROUP);
        verify(whatsApp).latestMessages("111@g.us", 5);
    }

    @Test
    void limitsTheNumberOfGroupsAndSaysHowManyWereLeftOut() {
        when(whatsApp.unreadGroups()).thenReturn(List.of(
                new UnreadGroup("222@g.us", "Society", 300, true),
                new UnreadGroup("333@g.us", "Office", 42, false)));
        when(whatsApp.latestMessages(anyString(), anyInt())).thenReturn(MESSAGES);

        String digest = new WhatsAppGroupDigestTool(whatsApp, () -> model).summarizeUnreadWhatsAppGroups(1, true);

        assertThat(digest).contains("## Society (300 unread)").contains("Not summarised: 1 more groups");
        assertThat(prompts).hasSize(1);
    }

    @Test
    void neverReadsSmallGroupsOrGroupsOfUnknownSize() {
        when(whatsApp.unreadGroups()).thenReturn(List.of(
                new UnreadGroup("1@g.us", "Close friends", 90, false),
                new UnreadGroup("2@g.us", "Unknown size", 60, false),
                new UnreadGroup("3@g.us", "Society", 30, false)));
        when(whatsApp.participantCount("1@g.us")).thenReturn(OptionalInt.of(WhatsAppGroupDigestTool.MAX_SMALL_GROUP_SIZE));
        when(whatsApp.participantCount("2@g.us")).thenReturn(OptionalInt.empty());
        when(whatsApp.participantCount("3@g.us")).thenReturn(OptionalInt.of(6));
        when(whatsApp.latestMessages(anyString(), anyInt())).thenReturn(MESSAGES);

        String digest = new WhatsAppGroupDigestTool(whatsApp, () -> model).summarizeUnreadWhatsAppGroups(null, null);

        assertThat(digest)
                .contains("## Society (30 unread)")
                .doesNotContain("Close friends").doesNotContain("Unknown size")
                .contains("Skipped for privacy: 2 small groups");
        verify(whatsApp, never()).latestMessages(eq("1@g.us"), anyInt());
        verify(whatsApp, never()).latestMessages(eq("2@g.us"), anyInt());
        assertThat(prompts).hasSize(1);
    }

    @Test
    void saysSoWhenOnlySmallGroupsAreUnread() {
        when(whatsApp.unreadGroups()).thenReturn(List.of(new UnreadGroup("1@g.us", "Family", 9, false)));
        when(whatsApp.participantCount("1@g.us")).thenReturn(OptionalInt.of(4));

        String digest = new WhatsAppGroupDigestTool(whatsApp, () -> { throw new AssertionError("no model call expected"); })
                .summarizeUnreadWhatsAppGroups(null, null);

        assertThat(digest).startsWith("No unread WhatsApp groups with more than 5 members.")
                .contains("Skipped for privacy: 1 small groups");
        verify(whatsApp, never()).latestMessages(anyString(), anyInt());
    }

    @Test
    void saysSoWhenThereIsNothingUnread() {
        when(whatsApp.unreadGroups()).thenReturn(List.of());

        String digest = new WhatsAppGroupDigestTool(whatsApp, () -> { throw new AssertionError("no model call expected"); })
                .summarizeUnreadWhatsAppGroups(5, true);

        assertThat(digest).isEqualTo("There are no unread WhatsApp group chats.");
    }

    @Test
    void stopsCallingTheModelOnceItsQuotaIsUsedUp() {
        when(whatsApp.unreadGroups()).thenReturn(List.of(
                new UnreadGroup("111@g.us", "Office", 42, false),
                new UnreadGroup("222@g.us", "Family", 5, false)));
        when(whatsApp.latestMessages(anyString(), anyInt())).thenReturn(MESSAGES);
        List<Prompt> attempts = new ArrayList<>();
        ChatClient exhausted = ChatClient.builder(prompt -> {
            attempts.add(prompt);
            throw new RuntimeException("429 . You exceeded your current quota");
        }).build();

        String digest = new WhatsAppGroupDigestTool(whatsApp, () -> exhausted).summarizeUnreadWhatsAppGroups(null, null);

        assertThat(attempts).hasSize(1);
        assertThat(digest)
                .contains("## Office (42 unread)\n- (could not summarise: model quota used up)")
                .contains("## Family (5 unread)\n- (skipped: model quota used up)");
    }
}

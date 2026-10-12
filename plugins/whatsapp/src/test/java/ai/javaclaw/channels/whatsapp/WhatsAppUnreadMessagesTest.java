package ai.javaclaw.channels.whatsapp;

import ai.javaclaw.channels.whatsapp.WhatsApp.Message;
import ai.javaclaw.channels.whatsapp.WhatsApp.UnreadGroup;
import ai.javaclaw.cli.CliRunner;
import ai.javaclaw.cli.CliRunner.CliResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WhatsAppUnreadMessagesTest {

    /** The shape of 'wacli chats list --unread --json'. */
    static final String UNREAD_CHATS = """
            {"success":true,"error":null,"data":[
              {"jid":"111@g.us","kind":"group","name":"Family","unread":true,"unread_count":5,"muted_until":0},
              {"jid":"222@g.us","kind":"group","name":"Society","unread":true,"unread_count":300,"muted_until":-1},
              {"jid":"333@g.us","kind":"group","name":"Office","unread":true,"unread_count":42,"muted_until":0},
              {"jid":"444@s.whatsapp.net","kind":"dm","name":"Alice","unread":true,"unread_count":3,"muted_until":0}
            ]}
            """;

    /** The shape of 'wacli messages list --json': newest first. */
    static final String MESSAGES = """
            {"success":true,"error":null,"data":{"fts":false,"messages":[
              {"Timestamp":"2026-10-09T16:04:16Z","SenderName":"Bob","FromMe":false,"Text":"Dinner at 8?","DisplayText":"","MediaType":"","MediaCaption":"","ReactionEmoji":"","Revoked":false},
              {"Timestamp":"2026-10-09T16:03:00Z","SenderName":"Bob","FromMe":false,"Text":"","DisplayText":"","MediaType":"","MediaCaption":"","ReactionEmoji":"👍","Revoked":false},
              {"Timestamp":"2026-10-09T16:02:00Z","SenderName":"Carol","FromMe":false,"Text":"","DisplayText":"","MediaType":"image","MediaCaption":"new house","ReactionEmoji":"","Revoked":false},
              {"Timestamp":"2026-10-09T16:01:00Z","SenderName":"","FromMe":true,"Text":"Hi all","DisplayText":"","MediaType":"","MediaCaption":"","ReactionEmoji":"","Revoked":false},
              {"Timestamp":"2026-10-09T16:00:00Z","SenderName":"Dan","FromMe":false,"Text":"deleted","DisplayText":"","MediaType":"","MediaCaption":"","ReactionEmoji":"","Revoked":true}
            ]}}
            """;

    @Test
    void readsUnreadGroupsMostUnreadFirstAndSkipsDirectChats() {
        List<UnreadGroup> groups = WhatsApp.unreadGroupsIn(UNREAD_CHATS, Instant.parse("2026-10-09T00:00:00Z"));

        assertThat(groups).containsExactly(
                new UnreadGroup("222@g.us", "Society", 300, true),
                new UnreadGroup("333@g.us", "Office", 42, false),
                new UnreadGroup("111@g.us", "Family", 5, false));
    }

    @Test
    void readsMessagesOldestFirstWithoutReactionsOrRevokedMessages() {
        List<Message> messages = WhatsApp.messagesIn(MESSAGES);

        assertThat(messages).extracting(Message::text)
                .containsExactly("Hi all", "[image] new house", "Dinner at 8?");
        assertThat(messages.getFirst().fromMe()).isTrue();
    }

    @Test
    void countsParticipantsAndTreatsAnEmptySnapshotAsUnknown() {
        String twoMembers = """
                {"success":true,"error":null,"data":[
                  {"group_jid":"1@g.us","user_jid":"a@s.whatsapp.net","role":"admin","updated_at":"2026-10-12T07:28:50Z"},
                  {"group_jid":"1@g.us","user_jid":"b@s.whatsapp.net","role":"member","updated_at":"2026-10-12T07:28:50Z"}
                ]}
                """;

        assertThat(WhatsApp.participantCountIn(twoMembers)).hasValue(2);
        assertThat(WhatsApp.participantCountIn("{\"success\":true,\"data\":[]}")).isEmpty();
        assertThat(WhatsApp.participantCountIn("{\"success\":true,\"data\":null}")).isEmpty();
        assertThat(WhatsApp.participantCountIn("not json")).isEmpty();
    }

    @Test
    void runsWacliReadOnly() throws Exception {
        CliRunner cliRunner = mock(CliRunner.class);
        when(cliRunner.run(anyList(), any(Duration.class))).thenReturn(new CliResult(0, MESSAGES, ""));
        WhatsApp whatsApp = new WhatsApp("me@s.whatsapp.net", cliRunner, mock(WacliSync.class));

        whatsApp.latestMessages("333@g.us", 40);

        verify(cliRunner).run(List.of("wacli", "--read-only", "messages", "list", "--chat", "333@g.us", "--limit", "40", "--json"),
                Duration.ofSeconds(10));
    }
}

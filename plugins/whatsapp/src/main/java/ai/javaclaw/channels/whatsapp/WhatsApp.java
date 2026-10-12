package ai.javaclaw.channels.whatsapp;

import ai.javaclaw.cli.CliRunner;
import ai.javaclaw.cli.CliRunner.CliResult;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.NamedInterface;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The one WhatsApp chat the assistant talks in: {@link #sendMessage} to send text there,
 * {@link #registerMessageReceiver} to be told about text arriving there. Messages in any other
 * chat are ignored.
 *
 * <p>It works by driving <a href="https://github.com/openclaw/wacli">wacli</a>, a small command
 * line tool that pairs as a WhatsApp Web linked device. {@link #start} leaves {@code wacli sync}
 * running in the background, wacli posts every incoming message to a local webhook, and
 * {@link #onIncomingMessage} hands it to the receivers. This is the only class that runs wacli.
 *
 * <p>Exposed to other modules so tools can read (never write) the user's other chats.
 */
@NamedInterface("whatsapp")
public class WhatsApp {

    @FunctionalInterface
    public interface MessageReceiver {
        void onMessage(String fromJidId, String message);
    }

    static final String INSTALL_HINT = "Install it (macOS: 'brew install openclaw/tap/wacli', "
            + "Linux: 'go install github.com/openclaw/wacli@latest').";

    private static final Logger LOGGER = LoggerFactory.getLogger(WhatsApp.class);

    /** The wacli binary, taken from the PATH. */
    private static final String WACLI = "wacli";

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);

    /** Returned when a wacli command could not be run at all: it is missing, or it took too long. */
    private static final CliResult COMMAND_FAILED = new CliResult(-1, "", "");

    /** How many of our own message IDs to remember, so we recognise wacli's echo of our replies. */
    private static final int REMEMBERED_SENT_IDS = 256;

    /** wacli's name for a one-to-one chat, as opposed to a group, newsletter or broadcast. */
    private static final String DIRECT_CHAT_KIND = "dm";

    private static final int MAX_CHATS_LISTED = 200;

    /** wacli's name for a group chat. */
    private static final String GROUP_CHAT_KIND = "group";

    /** Makes wacli refuse anything that would write to WhatsApp or its store, e.g. marking as read. */
    private static final String READ_ONLY = "--read-only";

    /**
     * JID of the chat we talk in: a contact's number followed by {@code @s.whatsapp.net}, a group
     * followed by {@code @g.us}, or a {@code @lid}.
     */
    private final String chatJid;

    private final CliRunner cliRunner;
    private final WacliSync sync;
    private final List<MessageReceiver> messageReceivers = new CopyOnWriteArrayList<>();
    private final Queue<String> sentMessageIds = new ConcurrentLinkedQueue<>();

    public WhatsApp(String chatJid, CliRunner cliRunner, String webhookUrl) {
        this.chatJid = chatJid;
        this.cliRunner = cliRunner;
        this.sync = new WacliSync(() -> startSyncProcess(webhookUrl));
    }

    WhatsApp(String chatJid, CliRunner cliRunner, WacliSync sync) {
        this.chatJid = chatJid;
        this.cliRunner = cliRunner;
        this.sync = sync;
    }

    /** @return {@code true} if the wacli command line is present and can be run */
    public boolean isInstalled() {
        return runWacli("version").isSuccess();
    }

    /** @return {@code true} if wacli is paired with a phone as a WhatsApp Web linked device */
    public boolean isPaired() {
        return runWacli("auth", "status", "--json").isSuccess();
    }

    /**
     * Connects to WhatsApp by leaving {@code wacli sync} running in the background.
     *
     * @return {@code true} if the connection was started, {@code false} if wacli is not installed
     */
    public boolean start() {
        if (!isInstalled()) {
            LOGGER.error("The wacli command line was not found, so the WhatsApp channel is disabled. {}", INSTALL_HINT);
            return false;
        }
        sync.start();
        return true;
    }

    /** Disconnects from WhatsApp. */
    public void stop() {
        sync.stop();
    }

    /** @return {@code true} while messages can be received and replies sent */
    public boolean isConnected() {
        return sync.isRunning();
    }

    /** Sends a text message to our chat. */
    public void sendMessage(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        if (!isConnected()) {
            LOGGER.warn("WhatsApp is not connected, cannot send message '{}'", message);
            return;
        }

        // --allow-self: wacli refuses to send to the linked account itself by default, which is
        // exactly the "Message Yourself" chat people use to talk to the assistant.
        CliResult result = runWacli("send", "text", "--to", chatJid, "--message", message, "--allow-self", "--json");
        if (!result.isSuccess()) {
            LOGGER.error("'wacli send' exited with code {}: {}", result.exitCode(), result.stderr().strip());
            return;
        }
        rememberSentMessage(sentMessageId(result.stdout()));
    }

    /**
     * Lists the one-to-one chats wacli knows about, most recent first, so onboarding can offer
     * them instead of asking for a raw JID. Groups, newsletters and broadcasts are left out on
     * purpose: we authorise by chat, and those have more than one person who could talk to us.
     *
     * @return the chats, or an empty list if wacli is unavailable or has synced nothing yet
     */
    public List<Chat> oneToOneChats() {
        CliResult result = runWacli("chats", "list", "--json", "--limit", String.valueOf(MAX_CHATS_LISTED));
        if (!result.isSuccess()) {
            LOGGER.warn("Could not list WhatsApp chats: {}", result.stderr().strip());
            return List.of();
        }
        return oneToOneChatsIn(result.stdout());
    }

    /** A chat the assistant could be pointed at. */
    public record Chat(String jid, String name) {
    }

    /**
     * Lists the group chats with unread messages, most unread first. Read-only: it never marks
     * anything as read.
     *
     * @return the groups, or an empty list if wacli is unavailable or has synced nothing yet
     */
    public List<UnreadGroup> unreadGroups() {
        CliResult result = runWacli(READ_ONLY, "chats", "list", "--unread", "--json", "--limit", String.valueOf(MAX_CHATS_LISTED));
        if (!result.isSuccess()) {
            LOGGER.warn("Could not list unread WhatsApp chats: {}", result.stderr().strip());
            return List.of();
        }
        return unreadGroupsIn(result.stdout(), Instant.now());
    }

    /** A group chat with unread messages. */
    public record UnreadGroup(String jid, String name, int unreadCount, boolean muted) {
    }

    /**
     * Reads the latest messages of any chat from wacli's local store, oldest first. Read-only.
     *
     * @return the messages, or an empty list if wacli is unavailable
     */
    public List<Message> latestMessages(String chatJid, int limit) {
        CliResult result = runWacli(READ_ONLY, "messages", "list", "--chat", chatJid, "--limit", String.valueOf(limit), "--json");
        if (!result.isSuccess()) {
            LOGGER.warn("Could not list WhatsApp messages of chat '{}': {}", chatJid, result.stderr().strip());
            return List.of();
        }
        return messagesIn(result.stdout());
    }

    /** A message as stored by wacli; {@code text} already includes a media caption or placeholder. */
    public record Message(Instant timestamp, String senderName, boolean fromMe, String text) {
    }

    /**
     * Counts the members of a group from wacli's local snapshot. Read-only.
     *
     * @return the number of participants, or empty if it is unknown (wacli unavailable, or the
     * group's participants were never synced)
     */
    public OptionalInt participantCount(String groupJid) {
        CliResult result = runWacli(READ_ONLY, "groups", "participants", "list", "--jid", groupJid, "--json");
        if (!result.isSuccess()) {
            LOGGER.warn("Could not list participants of WhatsApp group '{}': {}", groupJid, result.stderr().strip());
            return OptionalInt.empty();
        }
        return participantCountIn(result.stdout());
    }

    /** Registers a receiver to be told about every message arriving in our chat. */
    public void registerMessageReceiver(MessageReceiver messageReceiver) {
        messageReceivers.add(messageReceiver);
    }

    /** Called by {@link WhatsAppWebhookController} for every message wacli posts to us. */
    void onIncomingMessage(WacliWebhookPayload payload) {
        if (payload == null || payload.text() == null || payload.text().isBlank()) {
            return;
        }
        // Both must match. Checking only the sender would accept everything the configured person
        // types in *any* chat -- and when that person is you ("Message Yourself"), that is every
        // message you send to anyone. Checking only the chat would accept anybody else typing in it.
        if (!isOurJid(payload.chat()) || !isOurJid(payload.senderJid())) {
            LOGGER.debug("Ignoring WhatsApp message in chat '{}', we only answer in '{}'", payload.chat(), chatJid);
            return;
        }
        if (weSentThisOurselves(payload.id())) {
            // 'wacli sync' echoes our own replies back to us. We recognise them by message ID
            // rather than by FromMe, because that is what lets you chat with the assistant in your
            // own "Message Yourself" chat, where what you type also arrives with FromMe=true.
            return;
        }
        for (MessageReceiver messageReceiver : messageReceivers) {
            messageReceiver.onMessage(payload.chat(), payload.text());
        }
    }

    private boolean isOurJid(String incomingJid) {
        if (chatJid == null || chatJid.isBlank() || incomingJid == null) {
            return false;
        }
        return chatJid.trim().equalsIgnoreCase(incomingJid.trim());
    }

    private void rememberSentMessage(String messageId) {
        if (messageId == null) {
            LOGGER.debug("The message we just sent has no ID, so we may end up answering its echo");
            return;
        }
        sentMessageIds.add(messageId);
        if (sentMessageIds.size() > REMEMBERED_SENT_IDS) {
            sentMessageIds.poll();
        }
    }

    private boolean weSentThisOurselves(String messageId) {
        return messageId != null && sentMessageIds.contains(messageId);
    }

    // ----------------------------------------------------------------------------------------
    // Running wacli
    // ----------------------------------------------------------------------------------------

    /**
     * Starts the long-running {@code wacli sync} process. Its own output is dropped -- we read the
     * messages from the webhook instead -- but its errors are left visible on the console.
     */
    private Process startSyncProcess(String webhookUrl) throws IOException {
        List<String> command = wacliCommand("sync", "--follow", "--webhook", webhookUrl, "--webhook-allow-private");
        return new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
    }

    private CliResult runWacli(String... arguments) {
        List<String> command = wacliCommand(arguments);
        try {
            return cliRunner.run(command, COMMAND_TIMEOUT);
        } catch (IOException e) {
            LOGGER.debug("Could not run {}", command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return COMMAND_FAILED;
    }

    private static List<String> wacliCommand(String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(WACLI);
        command.addAll(List.of(arguments));
        return command;
    }

    /** Reads the one-to-one chats out of the JSON that {@code wacli chats list --json} prints. */
    private static List<Chat> oneToOneChatsIn(String listOutput) {
        try {
            ChatsResponse response = JSON_MAPPER.readValue(listOutput, ChatsResponse.class);
            if (response.data() == null) {
                return List.of();
            }
            return response.data().stream()
                    .filter(row -> DIRECT_CHAT_KIND.equals(row.kind()) && row.jid() != null)
                    .map(row -> new Chat(row.jid(), row.name() == null || row.name().isBlank() ? row.jid() : row.name()))
                    .toList();
        } catch (RuntimeException e) {
            LOGGER.warn("Could not read the output of 'wacli chats list'", e);
            return List.of();
        }
    }

    private record ChatsResponse(List<Row> data) {
        private record Row(String jid, String kind, String name) {
        }
    }

    /** Reads the unread group chats out of the JSON that {@code wacli chats list --unread --json} prints. */
    static List<UnreadGroup> unreadGroupsIn(String listOutput, Instant now) {
        try {
            UnreadChatsResponse response = JSON_MAPPER.readValue(listOutput, UnreadChatsResponse.class);
            if (response.data() == null) {
                return List.of();
            }
            return response.data().stream()
                    .filter(row -> GROUP_CHAT_KIND.equals(row.kind()) && row.jid() != null && row.unreadCount() > 0)
                    .map(row -> new UnreadGroup(row.jid(),
                            row.name() == null || row.name().isBlank() ? row.jid() : row.name(),
                            row.unreadCount(),
                            isMuted(row.mutedUntil(), now)))
                    .sorted(Comparator.comparingInt(UnreadGroup::unreadCount).reversed())
                    .toList();
        } catch (RuntimeException e) {
            LOGGER.warn("Could not read the output of 'wacli chats list --unread'", e);
            return List.of();
        }
    }

    /** wacli reports {@code muted_until} as 0 (not muted), -1 (muted forever) or an epoch second. */
    private static boolean isMuted(long mutedUntil, Instant now) {
        return mutedUntil < 0 || mutedUntil > now.getEpochSecond();
    }

    private record UnreadChatsResponse(List<Row> data) {
        private record Row(String jid, String kind, String name,
                           @JsonProperty("unread_count") int unreadCount,
                           @JsonProperty("muted_until") long mutedUntil) {
        }
    }

    /** Reads the number of participants out of the JSON that {@code wacli groups participants list --json} prints. */
    static OptionalInt participantCountIn(String listOutput) {
        try {
            ParticipantsResponse response = JSON_MAPPER.readValue(listOutput, ParticipantsResponse.class);
            if (response.data() == null || response.data().isEmpty()) {
                // An empty snapshot means "not synced", not "nobody is in it".
                return OptionalInt.empty();
            }
            return OptionalInt.of(response.data().size());
        } catch (RuntimeException e) {
            LOGGER.warn("Could not read the output of 'wacli groups participants list'", e);
            return OptionalInt.empty();
        }
    }

    private record ParticipantsResponse(List<Object> data) {
    }

    /** Reads the messages out of the JSON that {@code wacli messages list --json} prints, oldest first. */
    static List<Message> messagesIn(String listOutput) {
        try {
            MessagesResponse response = JSON_MAPPER.readValue(listOutput, MessagesResponse.class);
            if (response.data() == null || response.data().messages() == null) {
                return List.of();
            }
            List<Message> messages = new ArrayList<>();
            for (MessagesResponse.Row row : response.data().messages()) {
                String text = textOf(row);
                if (text == null || row.revoked() || (row.reactionEmoji() != null && !row.reactionEmoji().isBlank())) {
                    continue;
                }
                messages.add(new Message(parseTimestamp(row.timestamp()), row.senderName(), row.fromMe(), text));
            }
            // wacli lists newest first; a conversation reads better oldest first.
            messages.sort(Comparator.comparing(Message::timestamp, Comparator.nullsFirst(Comparator.naturalOrder())));
            return messages;
        } catch (RuntimeException e) {
            LOGGER.warn("Could not read the output of 'wacli messages list'", e);
            return List.of();
        }
    }

    private static String textOf(MessagesResponse.Row row) {
        String text = firstNonBlank(row.text(), row.displayText());
        if (row.mediaType() != null && !row.mediaType().isBlank()) {
            String caption = firstNonBlank(row.mediaCaption(), text);
            return "[" + row.mediaType() + "]" + (caption == null ? "" : " " + caption);
        }
        return text;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    private static Instant parseTimestamp(String timestamp) {
        try {
            return timestamp == null ? null : OffsetDateTime.parse(timestamp).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private record MessagesResponse(Data data) {
        private record Data(List<Row> messages) {
        }

        private record Row(@JsonProperty("Timestamp") String timestamp,
                           @JsonProperty("SenderName") String senderName,
                           @JsonProperty("FromMe") boolean fromMe,
                           @JsonProperty("Text") String text,
                           @JsonProperty("DisplayText") String displayText,
                           @JsonProperty("MediaType") String mediaType,
                           @JsonProperty("MediaCaption") String mediaCaption,
                           @JsonProperty("ReactionEmoji") String reactionEmoji,
                           @JsonProperty("Revoked") boolean revoked) {
        }
    }

    /** Reads the message ID out of the JSON that {@code wacli send --json} prints. */
    private static String sentMessageId(String sendOutput) {
        try {
            SendResponse response = JSON_MAPPER.readValue(sendOutput, SendResponse.class);
            String id = response.data() == null ? null : response.data().id();
            return (id == null || id.isBlank()) ? null : id;
        } catch (RuntimeException e) {
            LOGGER.debug("No message ID in the output of 'wacli send': {}", sendOutput, e);
            return null;
        }
    }

    private record SendResponse(Data data) {
        private record Data(String id) {
        }
    }
}

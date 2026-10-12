package ai.javaclaw.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;

@Component
public class DefaultAgent implements Agent {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgent.class);

    /**
     * Stored as the answer to a turn that failed. The memory advisor saves the question before the
     * model call but the answer only after it, so without this the question stays open and the
     * next successful turn answers it as well.
     */
    static final String FAILED_TURN_NOTE = "[No reply: answering this message failed and it was not answered. "
            + "Do not act on it unless the user asks again.]";

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;

    public DefaultAgent(ChatClient chatClient, ChatMemory chatMemory) {
        this.chatClient = chatClient;
        this.chatMemory = chatMemory;
    }

    @Override
    public String respondTo(String conversationId, String question) {
        try {
            return chatClient
                    .prompt(question)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call()
                    .content();
        } catch (RuntimeException e) {
            closeFailedTurn(conversationId);
            throw e;
        }
    }

    @Override
    public String respondTo(String conversationId, String question, ResponseListener listener) {
        StringBuilder fullResponse = new StringBuilder();
        try {
            chatClient
                    .prompt(question)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .stream()
                    .content()
                    .doOnNext(token -> {
                        fullResponse.append(token);
                        listener.onToken(token);
                    })
                    .blockLast();
        } catch (UnsupportedOperationException e) {
            // The configured model cannot stream — fall back to the blocking call
            String response = respondTo(conversationId, question);
            listener.onToken(response);
            listener.onComplete();
            return response;
        } catch (RuntimeException e) {
            log.warn("Streaming response failed for conversation {}", conversationId, e);
            closeFailedTurn(conversationId);
            listener.onError(summarizeError(e));
            return fullResponse.toString();
        }
        listener.onComplete();
        return fullResponse.toString();
    }

    @Override
    public <T> T prompt(String conversationId, String input, Class<T> result) {
        return chatClient
                .prompt(input)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .entity(result);
    }

    private void closeFailedTurn(String conversationId) {
        try {
            chatMemory.add(conversationId, new AssistantMessage(FAILED_TURN_NOTE));
        } catch (RuntimeException e) {
            log.warn("Could not mark the failed turn in conversation {}", conversationId, e);
        }
    }

    private static String summarizeError(Throwable ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}

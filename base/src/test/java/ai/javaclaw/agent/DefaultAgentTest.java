package ai.javaclaw.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import reactor.core.publisher.Flux;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DefaultAgentTest {

    @Mock ChatClient chatClient;
    @Mock ChatClient.ChatClientRequestSpec requestSpec;
    @Mock ChatClient.StreamResponseSpec streamSpec;
    @Mock ChatClient.CallResponseSpec callSpec;
    @Mock ResponseListener listener;
    @Mock ChatMemory chatMemory;

    DefaultAgent agent;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        agent = new DefaultAgent(chatClient, chatMemory);
        when(chatClient.prompt("hello")).thenReturn(requestSpec);
        when(requestSpec.advisors(any(Consumer.class))).thenReturn(requestSpec);
        lenient().when(requestSpec.stream()).thenReturn(streamSpec);
    }

    @Test
    void reportsEachTokenAndCompletionToListener() {
        when(streamSpec.content()).thenReturn(Flux.just("Hello ", "world"));

        String response = agent.respondTo("web", "hello", listener);

        assertThat(response).isEqualTo("Hello world");
        var inOrder = inOrder(listener);
        inOrder.verify(listener).onToken("Hello ");
        inOrder.verify(listener).onToken("world");
        inOrder.verify(listener).onComplete();
        verify(listener, never()).onError(any());
    }

    @Test
    void reportsErrorAndReturnsPartialResponseWhenStreamFails() {
        when(streamSpec.content()).thenReturn(Flux.concat(
                Flux.just("Hello "),
                Flux.error(new RuntimeException("boom"))));

        String response = agent.respondTo("web", "hello", listener);

        assertThat(response).isEqualTo("Hello ");
        var inOrder = inOrder(listener);
        inOrder.verify(listener).onToken("Hello ");
        inOrder.verify(listener).onError("boom");
        verify(listener, never()).onComplete();
        verify(chatMemory).add("web", new AssistantMessage(DefaultAgent.FAILED_TURN_NOTE));
    }

    @Test
    void closesTheTurnInMemoryWhenTheBlockingCallFails() {
        // Otherwise the unanswered question is still open and the next turn answers it too.
        when(requestSpec.call()).thenThrow(new RuntimeException("429 quota"));

        assertThatThrownBy(() -> agent.respondTo("whatsapp", "hello")).hasMessage("429 quota");

        verify(chatMemory).add("whatsapp", new AssistantMessage(DefaultAgent.FAILED_TURN_NOTE));
    }

    @Test
    void fallsBackToBlockingCallWhenModelDoesNotSupportStreaming() {
        when(streamSpec.content()).thenReturn(Flux.error(new UnsupportedOperationException("streaming is not supported")));
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("full response");

        String response = agent.respondTo("web", "hello", listener);

        assertThat(response).isEqualTo("full response");
        var inOrder = inOrder(listener);
        inOrder.verify(listener).onToken("full response");
        inOrder.verify(listener).onComplete();
        verify(listener, never()).onError(any());
        verify(chatMemory, never()).add(anyString(), any(AssistantMessage.class));
    }
}

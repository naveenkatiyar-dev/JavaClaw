package ai.javaclaw.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class UnknownToolCallbackResolverTest {

    @Test
    void unknownToolCallReturnsErrorToModelInsteadOfThrowing() {
        Prompt prompt = new Prompt(new UserMessage("list my files"), ToolCallingChatOptions.builder().build());
        AssistantMessage toolCall = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "taskSearchTool", "{}")))
                .build();
        ChatResponse response = new ChatResponse(List.of(new Generation(toolCall)));

        ToolExecutionResult result = UnknownToolCallbackResolver.toolCallingManager()
                .executeToolCalls(prompt, response);

        List<Message> history = result.conversationHistory();
        ToolResponseMessage toolResponse = (ToolResponseMessage) history.getLast();
        assertThat(toolResponse.getResponses()).singleElement().satisfies(r -> {
            assertThat(r.name()).isEqualTo("taskSearchTool");
            assertThat(r.responseData()).contains("there is no tool named 'taskSearchTool'");
        });
    }
}

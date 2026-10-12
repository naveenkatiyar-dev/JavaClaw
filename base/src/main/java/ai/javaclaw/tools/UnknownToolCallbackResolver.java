package ai.javaclaw.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

/**
 * Last-resort {@link ToolCallbackResolver} for tool names the model invented (e.g. {@code Bash} or a
 * misspelled {@code toolSearchTool}). Instead of letting Spring AI fail the whole response with
 * "No ToolCallback found", the call returns an error message so the model can correct itself.
 */
public class UnknownToolCallbackResolver implements ToolCallbackResolver {

    private static final Logger log = LoggerFactory.getLogger(UnknownToolCallbackResolver.class);

    /** A {@link ToolCallingManager} that answers unknown tool calls with an error instead of throwing. */
    public static ToolCallingManager toolCallingManager() {
        return ToolCallingManager.builder()
                .toolCallbackResolver(new UnknownToolCallbackResolver())
                .build();
    }

    @Override
    public ToolCallback resolve(String toolName) {
        return new UnknownToolCallback(toolName);
    }

    private record UnknownToolCallback(String toolName) implements ToolCallback {

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(toolName)
                    .description("Unknown tool")
                    .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            log.warn("Model called unknown tool '{}'; returning an error to the model", toolName);
            return "Error: there is no tool named '" + toolName + "'. "
                    + "Only call tools from your provided tool list, using their exact names.";
        }
    }
}

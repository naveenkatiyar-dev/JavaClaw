package ai.javaclaw.providers.ollama;

import ai.javaclaw.llm.LlmProviderProperties.ProviderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.ThinkOption;

import static org.assertj.core.api.Assertions.assertThat;

class OllamaChatModelFactoryTest {

    private final OllamaChatModelFactory factory = new OllamaChatModelFactory();

    @Test
    void appliesConfiguredContextLength() {
        ProviderConfig config = new ProviderConfig("ollama", null, null, "qwen3.5:4b-q4_K_M");
        config.setContextLength(16384);

        OllamaChatOptions options = ((OllamaChatModel) factory.create(config)).getOptions();

        assertThat(options.getModel()).isEqualTo("qwen3.5:4b-q4_K_M");
        assertThat(options.getNumCtx()).isEqualTo(16384);
    }

    @Test
    void leavesContextLengthToOllamaWhenNotConfigured() {
        ProviderConfig config = new ProviderConfig("ollama", null, null, "qwen3.5:4b-q4_K_M");

        OllamaChatOptions options = ((OllamaChatModel) factory.create(config)).getOptions();

        assertThat(options.getNumCtx()).isNull();
        assertThat(options.getThinkOption()).isNull();
    }

    @Test
    void disablesThinkingWhenConfigured() {
        ProviderConfig config = new ProviderConfig("ollama", null, null, "qwen3.5:9b-q4_K_M");
        config.setThinking(false);

        OllamaChatOptions options = ((OllamaChatModel) factory.create(config)).getOptions();

        assertThat(options.getThinkOption()).isEqualTo(ThinkOption.ThinkBoolean.DISABLED);
    }
}

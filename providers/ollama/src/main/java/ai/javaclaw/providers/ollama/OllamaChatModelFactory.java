package ai.javaclaw.providers.ollama;

import ai.javaclaw.llm.ChatModelFactory;
import ai.javaclaw.llm.LlmProviderProperties.ProviderConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Builds {@link OllamaChatModel} for the local, no-API-key {@code ollama} provider type.
 */
@Component
public class OllamaChatModelFactory implements ChatModelFactory {

    static final String DEFAULT_BASE_URL = "http://localhost:11434";

    /**
     * Local models on consumer hardware can take minutes per answer. A shorter timeout does not
     * make them faster: Spring AI retries, and the retry queues behind the abandoned request.
     */
    static final Duration READ_TIMEOUT = Duration.ofMinutes(10);

    @Override
    public boolean supports(String providerType) {
        return "ollama".equals(providerType);
    }

    @Override
    public ChatModel create(ProviderConfig config) {
        String baseUrl = (config.getBaseUrl() != null && !config.getBaseUrl().isBlank())
                ? config.getBaseUrl().trim()
                : DEFAULT_BASE_URL;
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(READ_TIMEOUT);
        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();

        OllamaChatOptions.Builder options = OllamaChatOptions.builder();
        if (config.getModel() != null && !config.getModel().isBlank()) {
            options.model(config.getModel());
        }
        // Ollama otherwise loads models with a small default window, silently truncating the agent's
        // system prompt + tool definitions.
        if (config.getContextLength() != null && config.getContextLength() > 0) {
            options.numCtx(config.getContextLength());
        }
        // Reasoning models (qwen3.5, deepseek-r1, ...) think before every answer, which on a laptop
        // can take minutes. Left unset, the model decides.
        if (Boolean.FALSE.equals(config.getThinking())) {
            options.disableThinking();
        } else if (Boolean.TRUE.equals(config.getThinking())) {
            options.enableThinking();
        }
        return OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(options.build())
                .build();
    }
}

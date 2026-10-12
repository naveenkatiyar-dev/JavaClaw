package ai.javaclaw.tools.whatsapp;

import ai.javaclaw.channels.whatsapp.WhatsApp;
import ai.javaclaw.llm.ChatClientRegistry;
import ai.javaclaw.llm.LlmProviderProperties;
import ai.javaclaw.tools.AutoDiscoveredTool;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(afterName = "ai.javaclaw.channels.whatsapp.WhatsAppChannelAutoConfiguration")
@ConditionalOnProperty(prefix = "agent.channels.whatsapp", name = "enabled", havingValue = "true")
public class WhatsAppToolsAutoConfiguration {

    @Bean
    public AutoDiscoveredTool<WhatsAppGroupDigestTool> autoDiscoveredWhatsAppGroupDigestTool(WhatsApp whatsApp,
                                                                                              ChatClientRegistry chatClientRegistry) {
        // A plain client: no tools, no memory, so group messages cannot trigger any action.
        return new AutoDiscoveredTool<>(new WhatsAppGroupDigestTool(whatsApp,
                () -> chatClientRegistry.getOrDefault(LlmProviderProperties.DEFAULT_PROVIDER_NAME)));
    }
}

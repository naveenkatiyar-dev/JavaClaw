package ai.javaclaw.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ModelErrorsTest {

    @Test
    void findsAQuotaErrorAnywhereInTheCauseChain() {
        RuntimeException failure = new RuntimeException("Failed to generate content",
                new IllegalStateException("429 . You exceeded your current quota"));

        assertThat(ModelErrors.isQuotaExceeded(failure)).isTrue();
    }

    @Test
    void otherFailuresAreNotQuotaErrors() {
        assertThat(ModelErrors.isQuotaExceeded(new RuntimeException("boom", new IllegalStateException("404 model not found")))).isFalse();
        assertThat(ModelErrors.isQuotaExceeded(new RuntimeException((String) null))).isFalse();
    }
}

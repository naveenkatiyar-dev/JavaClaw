package ai.javaclaw.llm;

/** Classifies failures of model calls, independent of the provider SDK. */
public final class ModelErrors {

    private ModelErrors() {
    }

    /** Model SDKs differ in exception types, but all report HTTP 429 or a quota message somewhere in the cause chain. */
    public static boolean isQuotaExceeded(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && (message.contains("429") || message.contains("RESOURCE_EXHAUSTED")
                    || message.toLowerCase().contains("quota"))) {
                return true;
            }
        }
        return false;
    }
}

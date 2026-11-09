package app.morphe.extension.shared.settings;

/**
 * Debug logging level of {@link BaseSettings#DEBUG_LEVEL}.
 */
public enum DebugLevel {
    DISABLED,
    /**
     * Debug logs.
     */
    DEBUG,
    /**
     * Debug logs, and also the verbose logs that are too frequent or too detailed for normal debugging.
     */
    VERBOSE
}

package app.morphe.extension.shared;

import static app.morphe.extension.shared.settings.BaseSettings.DEBUG;
import static app.morphe.extension.shared.settings.BaseSettings.DEBUG_STACKTRACE;
import static app.morphe.extension.shared.settings.BaseSettings.DEBUG_TOAST_ON_ERROR;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Deque;
import java.util.Objects;
import java.util.SequencedCollection;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.settings.BaseSettings;

/**
 * Morphe specific logger. Logging is done to standard device log (accessible through ADB),
 * and additionally accessible through {@link #getLogBuffer()}.
 *
 * All methods are thread safe, and are safe to call even
 * if {@link Utils#getContext()} is not available.
 */
public class Logger {

    /** Maximum byte size of all buffer entries. Must be less than Android's 1 MB Binder transaction limit. */
    private static final int BUFFER_MAX_BYTES = 900_000;
    /** Limit number of log lines. */
    private static final int BUFFER_MAX_SIZE = 10_000;

    private static final Deque<String> logBuffer = new ConcurrentLinkedDeque<>();
    private static final AtomicInteger logBufferByteSize = new AtomicInteger();

    public static String getFilteredLogs() {
        StringBuilder filteredOutput = new StringBuilder(logBufferByteSize.get());

        for (String log : logBuffer) {
            filteredOutput.append(log).append('\n');
        }

        return filteredOutput.toString().trim();
    }

    private static void appendToLogBuffer(String message) {
        Objects.requireNonNull(message);

        // It's very important that no Settings are used in this method,
        // as this code is used when a context is not set and thus referencing
        // a setting will crash the app.
        logBuffer.addLast(message);
        int newSize = logBufferByteSize.addAndGet(message.length());

        // Remove the oldest entries if over the log size limits.
        while (newSize > BUFFER_MAX_BYTES || logBuffer.size() > BUFFER_MAX_SIZE) {
            String removed = logBuffer.pollFirst();
            if (removed == null) {
                // Thread race of two different calls to this method, and the other thread won.
                return;
            }

            newSize = logBufferByteSize.addAndGet(-removed.length());
        }
    }

    public static Deque<String> getLogBuffer() {
        return logBuffer;
    }

    public static void clearLogBufferData() {
        // Cannot simply clear the log buffer because there is no
        // write lock for both the deque and the atomic int.
        // Instead, pop off log entries and decrement the size one by one.
        while (!logBuffer.isEmpty()) {
            String removed = logBuffer.pollFirst();
            if (removed != null) {
                logBufferByteSize.addAndGet(-removed.length());
            }
        }
    }

    /**
     * Log messages using lambdas.
     */
    @FunctionalInterface
    public interface LogMessage {
        /**
         * @return Logger string message. This method is only called if logging is enabled.
         */
        @NonNull
        String buildMessageString();
    }

    private enum LogLevel {
        DEBUG,
        INFO,
        ERROR
    }

    /**
     * Log tag prefix. Only used for system logging.
     */
    private static final String MORPHE_LOG_TAG_PREFIX = "morphe: ";

    private static final String LOGGER_CLASS_NAME = Logger.class.getName();

    /**
     * @return For outer classes, this returns {@link Class#getSimpleName()}.
     * For static, inner, or anonymous classes, this returns the simple name of the enclosing class.
     * <br>
     * For example, each of these classes returns 'SomethingView':
     * <code>
     * com.company.SomethingView
     * com.company.SomethingView$StaticClass
     * com.company.SomethingView$1
     * </code>
     */
    private static String getOuterClassSimpleName(Object obj) {
        Class<?> logClass = obj.getClass();
        String fullClassName = logClass.getName();
        final int dollarSignIndex = fullClassName.indexOf('$');
        if (dollarSignIndex < 0) {
            return logClass.getSimpleName(); // Already an outer class.
        }

        // Class is inner, static, or anonymous.
        // Parse the simple name full name.
        // A class with no package returns index of -1, but incrementing gives index zero which is correct.
        final int simpleClassNameStartIndex = fullClassName.lastIndexOf('.') + 1;
        return fullClassName.substring(simpleClassNameStartIndex, dollarSignIndex);
    }

    /**
     * Internal method to handle logging to Android Log and internally to appends the log message,
     * stack trace (if enabled), and exception (if present) to logBuffer with class name but
     * without 'morphe:' prefix.
     *
     * @param logLevel          The log level.
     * @param message           Log message object.
     * @param ex                Optional exception.
     * @param includeStackTrace If the current stack should be included.
     * @param showToast         If a toast is to be shown.
     */
    private static void logInternal(LogLevel logLevel, LogMessage message, @Nullable Throwable ex,
                                    boolean includeStackTrace, boolean showToast) {
        // It's very important that no Settings are used in this method,
        // as this code is used when a context is not set and thus referencing
        // a setting will crash the app.
        String messageString = message.buildMessageString();
        String className = getOuterClassSimpleName(message);

        String logText = messageString;

        // Append exception message if present.
        if (ex != null) {
            var exceptionMessage = ex.getMessage();
            if (exceptionMessage != null) {
                logText += "\nException: " + exceptionMessage;
            }
        }

        if (includeStackTrace) {
            var sw = new StringWriter();
            new Throwable().printStackTrace(new PrintWriter(sw));
            String stackTrace = sw.toString();
            // Remove the stacktrace elements of this class.
            final int loggerIndex = stackTrace.lastIndexOf(LOGGER_CLASS_NAME);
            final int loggerBegins = stackTrace.indexOf('\n', loggerIndex);
            logText += stackTrace.substring(loggerBegins);
        }

        // Do not include "morphe:" prefix in clipboard logs.
        String managerToastString = className + ": " + logText;
        appendToLogBuffer(managerToastString);

        String logTag = MORPHE_LOG_TAG_PREFIX + className;
        switch (logLevel) {
            case DEBUG:
                if (ex == null) Log.d(logTag, logText);
                else Log.d(logTag, logText, ex);
                break;
            case INFO:
                if (ex == null) Log.i(logTag, logText);
                else Log.i(logTag, logText, ex);
                break;
            case ERROR:
                if (ex == null) Log.e(logTag, logText);
                else Log.e(logTag, logText, ex);
                break;
        }

        if (showToast) {
            Utils.showToastLong(managerToastString);
        }
    }

    private static boolean shouldLogDebug() {
        // If the app is still starting up and the context is not yet set,
        // then allow debug logging regardless what the debug setting actually is.
        return Utils.context == null || DEBUG.get();
    }

    private static boolean shouldShowErrorToast() {
        return Utils.context != null && DEBUG_TOAST_ON_ERROR.get();
    }

    private static boolean includeStackTrace() {
        return Utils.context != null && DEBUG_STACKTRACE.get();
    }

    /**
     * Logs debug messages under the outer class name of the code calling this method.
     * <p>
     * Whenever possible, the log string should be constructed entirely inside
     * {@link LogMessage#buildMessageString()} so the performance cost of
     * building strings is paid only if {@link BaseSettings#DEBUG} is enabled.
     */
    public static void printDebug(LogMessage message) {
        printDebug(message, null);
    }

    /**
     * Logs debug messages under the outer class name of the code calling this method.
     * <p>
     * Whenever possible, the log string should be constructed entirely inside
     * {@link LogMessage#buildMessageString()} so the performance cost of
     * building strings is paid only if {@link BaseSettings#DEBUG} is enabled.
     */
    public static void printDebug(LogMessage message, @Nullable Exception ex) {
        if (shouldLogDebug()) {
            logInternal(LogLevel.DEBUG, message, ex, includeStackTrace(), false);
        }
    }

    /**
     * Logs information messages using the outer class name of the code calling this method.
     */
    public static void printInfo(LogMessage message) {
        printInfo(message, null);
    }

    /**
     * Logs information messages using the outer class name of the code calling this method.
     */
    public static void printInfo(LogMessage message, @Nullable Exception ex) {
        logInternal(LogLevel.INFO, message, ex, includeStackTrace(), false);
    }

    /**
     * Logs exceptions under the outer class name of the code calling this method.
     * Appends the log message, exception (if present), and toast message (if enabled) to logBuffer.
     */
    public static void printException(LogMessage message) {
        printException(message, null);
    }

    /**
     * Logs exceptions under the outer class name of the code calling this method.
     * <p>
     * If the calling code is showing its own error toast,
     * instead use {@link #printInfo(LogMessage, Exception)}
     *
     * @param message          log message
     * @param ex               exception (optional)
     */
    public static void printException(LogMessage message, @Nullable Throwable ex) {
        logInternal(LogLevel.ERROR, message, ex, includeStackTrace(), shouldShowErrorToast());
    }
}

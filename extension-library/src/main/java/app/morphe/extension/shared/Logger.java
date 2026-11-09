/*
 * Copyright 2025 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original code hard forked from:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 */

package app.morphe.extension.shared;

import static app.morphe.extension.shared.settings.BaseSettings.DEBUG_LEVEL;
import static app.morphe.extension.shared.settings.BaseSettings.DEBUG_STACKTRACE;
import static app.morphe.extension.shared.settings.BaseSettings.DEBUG_TOAST_ON_ERROR;

import android.util.Log;

import androidx.annotation.Nullable;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.DebugLevel;

/**
 * Morphe specific logger. Logging is done to standard device log (accessible through ADB),
 * and additionally accessible through {@link #getLogBuffer()}.
 * <p>
 * All methods are thread safe, and are safe to call even
 * if {@link Utils#getContext()} is not available.
 */
@SuppressWarnings("unused")
public class Logger {

    /**
     * Maximum character length safe for Android's 1 MB Binder transaction limit (used for Clipboard).
     */
    public static final int CLIPBOARD_MAX_LENGTH = 900_000;
    /**
     * Maximum character size of all buffer entries kept in memory (~10 MB).
     */
    private static final int BUFFER_MAX_BYTES = 10_000_000;
    /**
     * Limit number of log entries in memory.
     */
    private static final int BUFFER_MAX_SIZE = 100_000;

    private static final Deque<String> logBuffer = new ConcurrentLinkedDeque<>();
    private static final AtomicInteger logBufferByteSize = new AtomicInteger();

    private static String lastLogMessage = null;
    private static int lastLogRepeatCount = 0;

    public static String getFilteredLogs() {
        StringBuilder filteredOutput = new StringBuilder(Math.min(logBufferByteSize.get(), BUFFER_MAX_BYTES));

        for (String log : logBuffer) {
            filteredOutput.append(log).append('\n');
        }

        return filteredOutput.toString().trim();
    }

    private static synchronized void appendToLogBuffer(String message) {
        Objects.requireNonNull(message);

        // It's very important that no Settings are used in this method, as this code is used when
        // a context is not set and thus referencing a setting will crash the app. Deduplicate
        // consecutive identical log messages to prevent spam from evicting older logs.
        if (message.equals(lastLogMessage)) {
            lastLogRepeatCount++;
            String previous = logBuffer.pollLast();
            if (previous != null) {
                logBufferByteSize.addAndGet(-previous.length());
            }
            String repeatedMessage = message + " [x" + (lastLogRepeatCount + 1) + "]";
            logBuffer.addLast(repeatedMessage);
            logBufferByteSize.addAndGet(repeatedMessage.length());
            return;
        }

        lastLogMessage = message;
        lastLogRepeatCount = 0;

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

    public static synchronized void clearLogBufferData() {
        lastLogMessage = null;
        lastLogRepeatCount = 0;
        logBuffer.clear();
        logBufferByteSize.set(0);
    }

    /**
     * Log messages using lambdas.
     */
    @FunctionalInterface
    public interface LogMessage {
        /**
         * @return Logger string message, or null to not log anything.
         *         This method is only called if logging is enabled, so any checks that are
         *         only needed to decide what to log can be done here instead of before logging.
         *         Null is meant for debug and verbose messages.
         */
        @Nullable
        String buildMessageString();
    }

    private enum LogLevel {
        VERBOSE,
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
        if (messageString == null) {
            return;
        }
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
            case VERBOSE:
                if (ex == null) Log.v(logTag, logText);
                else Log.v(logTag, logText, ex);
                break;
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

    /**
     * @return If debug logging is enabled. Also true while the app is still starting up
     *         and the context is not yet set, because the setting cannot be read yet.
     *         Code that only builds a log message does not need to check this,
     *         as {@link #printDebug(LogMessage)} already does.
     */
    public static boolean isDebugEnabled() {
        // If the app is still starting up and the context is not yet set,
        // then allow debug logging regardless what the debug setting actually is.
        return Utils.context == null || DEBUG_LEVEL.get() != DebugLevel.DISABLED;
    }

    /**
     * @return If verbose logging is enabled. Also true while the app is still starting up
     *         and the context is not yet set, because the setting cannot be read yet.
     *         Code that only builds a log message does not need to check this,
     *         as {@link #printVerbose(LogMessage)} already does.
     */
    public static boolean isVerboseEnabled() {
        return Utils.context == null || DEBUG_LEVEL.get() == DebugLevel.VERBOSE;
    }

    private static boolean shouldShowErrorToast() {
        return Utils.context != null && DEBUG_TOAST_ON_ERROR.get();
    }

    private static boolean includeStackTrace() {
        return Utils.context != null && DEBUG_STACKTRACE.get();
    }

    /**
     * Logs verbose messages under the outer class name of the code calling this method.
     * Verbose messages are only logged if {@link BaseSettings#DEBUG_LEVEL} is {@link DebugLevel#VERBOSE}.
     * Use this for messages that are too frequent or too detailed for normal debugging.
     * <p>
     * Whenever possible, the log string should be constructed entirely inside
     * {@link LogMessage#buildMessageString()} so the performance cost of
     * building strings is paid only if verbose logging is enabled.
     */
    public static void printVerbose(LogMessage message) {
        printVerbose(message, null);
    }

    /**
     * Logs verbose messages under the outer class name of the code calling this method.
     * Verbose messages are only logged if {@link BaseSettings#DEBUG_LEVEL} is {@link DebugLevel#VERBOSE}.
     * Use this for messages that are too frequent or too detailed for normal debugging.
     * <p>
     * Whenever possible, the log string should be constructed entirely inside
     * {@link LogMessage#buildMessageString()} so the performance cost of
     * building strings is paid only if verbose logging is enabled.
     */
    public static void printVerbose(LogMessage message, @Nullable Exception ex) {
        if (isVerboseEnabled()) {
            logInternal(LogLevel.VERBOSE, message, ex, includeStackTrace(), false);
        }
    }

    /**
     * Logs debug messages under the outer class name of the code calling this method.
     * <p>
     * Whenever possible, the log string should be constructed entirely inside
     * {@link LogMessage#buildMessageString()} so the performance cost of
     * building strings is paid only if debug logging is enabled.
     */
    public static void printDebug(LogMessage message) {
        printDebug(message, null);
    }

    /**
     * Logs debug messages under the outer class name of the code calling this method.
     * <p>
     * Whenever possible, the log string should be constructed entirely inside
     * {@link LogMessage#buildMessageString()} so the performance cost of
     * building strings is paid only if debug logging is enabled.
     */
    public static void printDebug(LogMessage message, @Nullable Exception ex) {
        if (isDebugEnabled()) {
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

    /**
     * Logs exceptions under the outer class name of the code calling this method,
     * but only if debug logging is enabled.
     * <p>
     * Use this for unexpected situations that do no harm to the user but that are relevant
     * to developers, such as an unknown UI component or blocking the main thread.
     */
    public static void printExceptionIfDebug(LogMessage message) {
        printExceptionIfDebug(message, null);
    }

    /**
     * Logs exceptions under the outer class name of the code calling this method,
     * but only if debug logging is enabled.
     * <p>
     * Use this for unexpected situations that do no harm to the user but that are relevant
     * to developers, such as an unknown UI component or blocking the main thread.
     */
    public static void printExceptionIfDebug(LogMessage message, @Nullable Throwable ex) {
        if (isDebugEnabled()) {
            printException(message, ex);
        }
    }
}

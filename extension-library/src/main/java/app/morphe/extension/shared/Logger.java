package app.morphe.extension.shared;

import static app.morphe.extension.shared.settings.BaseSettings.DEBUG;
import static app.morphe.extension.shared.settings.BaseSettings.DEBUG_STACKTRACE;
import static app.morphe.extension.shared.settings.BaseSettings.DEBUG_TOAST_ON_ERROR;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.EnumSetting;
import app.morphe.extension.shared.settings.FloatSetting;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.Setting;

/**
 * Morphe specific logger. Logging is done to standard device log (accessible through ADB),
 * and additionally accessible through {@link #getLogBuffer()}.
 * Each line of the buffer starts with the time, the level and the thread, because the buffer is what users export.
 * <p>
 * The logger also keeps a timeline of the app state ({@link #traceState(String, Object)},
 * {@link #traceEvent(String, String)}, {@link #traceOverride(String, boolean, boolean)} and the errors),
 * which is recorded whether debug logging is on or not. Logs exported after a bug that can't be
 * reproduced then still show how the app got there, even when debug logging was turned on afterwards
 * or the verbose logs pushed the older lines out of the buffer. See {@link #buildReport(Context)}.
 * <p>
 * All methods are thread safe, and are safe to call even
 * if {@link Utils#getContext()} is not available.
 * <p>
 * Only APIs available on the minimum SDK are used here. Newer API calls are outlined by R8 into
 * synthetic classes named after this class, and those names collide with the different synthetic
 * classes of other extensions that also contain this class.
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
    private static String lastLogMessageTime = null;
    private static int lastLogRepeatCount = 0;

    /**
     * Time zone of the log times, read once because {@link TimeZone#getDefault()} returns a copy.
     */
    private static final TimeZone TIME_ZONE = TimeZone.getDefault();

    private static final int TIMELINE_MAX_ENTRIES = 1500;
    private static final int PREVIOUS_TIMELINE_MAX_ENTRIES = 500;
    /**
     * Maximum length of the error entries of the timeline. The full error is in the log buffer.
     */
    private static final int TIMELINE_ERROR_MAX_LENGTH = 400;
    private static final int REPORT_SETTING_VALUE_MAX_LENGTH = 80;

    private static final String TIMELINE_FILE_NAME = "morphe_timeline.txt";
    private static final String PREVIOUS_TIMELINE_FILE_NAME = "morphe_timeline_previous.txt";

    private static final Deque<String> timeline = new ConcurrentLinkedDeque<>();
    private static final AtomicInteger timelineSize = new AtomicInteger();

    /**
     * Last value of each traced state and boolean override.
     */
    private static final Map<String, Object> tracedStates = new ConcurrentHashMap<>();

    /**
     * Last original and returned value of each traced number override, as raw bits.
     */
    private static final Map<String, long[]> tracedNumberOverrides = new ConcurrentHashMap<>();

    /**
     * Last original and returned value of each traced string override.
     */
    private static final Map<String, String[]> tracedStringOverrides = new ConcurrentHashMap<>();

    /**
     * Traced states can't be null in the map.
     */
    private static final String NULL_STATE = "null";

    private static final String[] BOOLEAN_OVERRIDES = {
            "false (= app)", "true (app: false)", "false (app: true)", "true (= app)"
    };

    private static final List<LogMessage> reportSections = new CopyOnWriteArrayList<>();

    /**
     * Settings whose values may identify the user or hold credentials. Only their length is exported.
     */
    private static final Pattern PRIVATE_SETTING_KEY = Pattern.compile(
            "token|key|password|username|user_id|arl|cookie|email|auth|account|session|host|playlist"
                    + "|search|phrase|filter_strings|whitelist|cache|data|items|times|24h|folder|path|file|name");

    /**
     * Setting values that are exported as they are: numbers, colors, flag lists and option names.
     */
    private static final Pattern PLAIN_SETTING_VALUE = Pattern.compile("[\\w#.,:\\-\\s]*");

    /**
     * How long new timeline entries wait before they are saved, so a process that ends without any callback,
     * such as for a native crash, an ANR or a kill while it's shown, loses at most this time of its timeline.
     */
    private static final long TIMELINE_SAVE_DELAY_MILLIS = 5000;

    private static final AtomicBoolean timelineSavePending = new AtomicBoolean();

    private static volatile boolean timelineStarted;

    @Nullable
    private static volatile File timelineDirectory;

    /**
     * Elapsed realtime when the timeline started, which is about when the process started.
     */
    private static volatile long timelineStartRealtime;

    public static String getFilteredLogs() {
        StringBuilder filteredOutput = new StringBuilder(Math.min(logBufferByteSize.get(), BUFFER_MAX_BYTES));

        for (String log : logBuffer) {
            filteredOutput.append(log).append('\n');
        }

        return filteredOutput.toString().trim();
    }

    /**
     * @param time    Time of the message, which is not compared to find repeated messages.
     * @param header  Level and thread of the message.
     * @param message The message.
     */
    private static synchronized void appendToLogBuffer(String time, String header, String message) {
        Objects.requireNonNull(message);

        // It's very important that no Settings are used in this method, as this code is used when
        // a context is not set and thus referencing a setting will crash the app. Deduplicate
        // consecutive identical log messages to prevent spam from evicting older logs.
        String headerAndMessage = header + message;
        if (headerAndMessage.equals(lastLogMessage)) {
            lastLogRepeatCount++;
            String previous = logBuffer.pollLast();
            if (previous != null) {
                logBufferByteSize.addAndGet(-previous.length());
            }
            String repeatedMessage = lastLogMessageTime + ' ' + headerAndMessage
                    + " [x" + (lastLogRepeatCount + 1) + ", last " + time + "]";
            logBuffer.addLast(repeatedMessage);
            logBufferByteSize.addAndGet(repeatedMessage.length());
            return;
        }

        lastLogMessage = headerAndMessage;
        lastLogMessageTime = time;
        lastLogRepeatCount = 0;

        String line = time + ' ' + headerAndMessage;
        logBuffer.addLast(line);
        int newSize = logBufferByteSize.addAndGet(line.length());

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
        lastLogMessageTime = null;
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

        // Append the exception and where it was thrown, which the stack trace of the logger call does not show.
        // The exception is also passed to the device log, but only the log buffer is exported by users.
        if (ex != null) {
            logText += "\nException: " + describeThrowable(ex);
            Throwable cause = ex.getCause();
            // Limited, because a cause can be its own cause further down the chain.
            for (int depth = 0; cause != null && depth < 5; depth++, cause = cause.getCause()) {
                logText += "\nCaused by: " + describeThrowable(cause);
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

            if (ex != null) {
                var exceptionStackTrace = new StringWriter();
                ex.printStackTrace(new PrintWriter(exceptionStackTrace));
                logText += "\nException stack trace:\n" + exceptionStackTrace;
            }
        }

        // Do not include "morphe:" prefix in clipboard logs.
        String managerToastString = className + ": " + logText;
        String time = formatTime(System.currentTimeMillis());
        String threadName = Thread.currentThread().getName();
        appendToLogBuffer(time, logLevel.name().charAt(0) + " [" + threadName + "] ", managerToastString);

        if (logLevel == LogLevel.ERROR) {
            String error = ex == null ? managerToastString : className + ": " + messageString + " (" + ex + ")";
            addTimelineEntry(time, threadName, "Error", error.length() > TIMELINE_ERROR_MAX_LENGTH
                    ? error.substring(0, TIMELINE_ERROR_MAX_LENGTH) + "..."
                    : error);
        }

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

    /**
     * @return The type and message of the exception, and the first frame of Morphe code that it went through,
     *         or the frame that threw it.
     */
    private static String describeThrowable(Throwable ex) {
        StackTraceElement[] frames = ex.getStackTrace();
        if (frames.length == 0) return ex.toString();

        StackTraceElement frame = frames[0];
        for (StackTraceElement element : frames) {
            if (element.getClassName().startsWith("app.morphe.")) {
                frame = element;
                break;
            }
        }
        return ex + " at " + frame + (frame == frames[0] ? "" : " (thrown at " + frames[0] + ")");
    }

    /**
     * @return The local time of day with milliseconds, such as 13:05:42.137.
     */
    private static String formatTime(long millis) {
        long dayMillis = (millis + TIME_ZONE.getOffset(millis)) % 86_400_000L;
        if (dayMillis < 0) dayMillis += 86_400_000L;

        StringBuilder builder = new StringBuilder(12);
        appendPadded(builder, dayMillis / 3_600_000L, 2).append(':');
        appendPadded(builder, dayMillis / 60_000L % 60, 2).append(':');
        appendPadded(builder, dayMillis / 1000L % 60, 2).append('.');
        return appendPadded(builder, dayMillis % 1000, 3).toString();
    }

    private static StringBuilder appendPadded(StringBuilder builder, long value, int digits) {
        if (digits == 3 && value < 100) builder.append('0');
        if (value < 10) builder.append('0');
        return builder.append(value);
    }

    private static void addTimelineEntry(String time, String threadName, String key, String message) {
        if (!timelineStarted && Utils.context != null) {
            // Hooks can trace before the context is set and the timeline starts.
            startTimeline(Utils.context);
        }

        timeline.addLast(time + " [" + threadName + "] " + key + ": " + message);
        if (timelineSize.incrementAndGet() > TIMELINE_MAX_ENTRIES && timeline.pollFirst() != null) {
            timelineSize.decrementAndGet();
        }

        File directory = timelineDirectory;
        if (directory != null && timelineSavePending.compareAndSet(false, true)) {
            Utils.runOnMainThreadDelayed(() -> Utils.runOnBackgroundThread(() -> {
                timelineSavePending.set(false);
                saveTimeline(directory, new ArrayList<>(timeline));
            }), TIMELINE_SAVE_DELAY_MILLIS);
        }
    }

    /**
     * Adds an entry to the timeline, and to the logs if debug logging is on.
     */
    private static void trace(String key, String message) {
        String time = formatTime(System.currentTimeMillis());
        String threadName = Thread.currentThread().getName();
        addTimelineEntry(time, threadName, key, message);

        if (shouldLogDebug()) {
            String text = key + ": " + message;
            appendToLogBuffer(time, "T [" + threadName + "] ", text);
            Log.d(MORPHE_LOG_TAG_PREFIX + key, message);
        }
    }

    /**
     * Traces a state, such as the player type or the selected tab, when it differs
     * from the last value traced with the same key.
     * The states are recorded in the timeline whether debug logging is on or not.
     *
     * @param key Name of the state, such as "PlayerType".
     */
    public static void traceState(String key, @Nullable Object value) {
        Object newValue = value == null ? NULL_STATE : value;
        Object oldValue = tracedStates.get(key);
        if (newValue.equals(oldValue)) return;

        tracedStates.put(key, newValue);
        trace(key, oldValue == null ? newValue.toString() : oldValue + " -> " + newValue);
    }

    /**
     * Traces an event that is not a state, such as an activity lifecycle callback or a fallback that was used.
     * Events are recorded in the timeline whether debug logging is on or not,
     * so only use this for events that are not frequent.
     */
    public static void traceEvent(String key, String message) {
        trace(key, message);
    }

    /**
     * Traces the value a hook returns in place of the value of the app, such as a server A/B flag,
     * when the hook starts or stops changing the value of the app, or returns another value in its place.
     * The first value is always traced. The app can change its own value often, such as the visibility
     * of a button, so its changes are not traced while the hook returns it unchanged.
     * Recorded whether debug logging is on or not.
     *
     * @param key Name of the hook, such as "MiniplayerPatch.getModernMiniplayerOverride".
     * @return The returned value, so hooks can return the result of this method.
     */
    public static boolean traceOverride(String key, boolean original, boolean value) {
        if (original == value) {
            Object last = tracedStates.get(key);
            if (last == BOOLEAN_OVERRIDES[0] || last == BOOLEAN_OVERRIDES[3]) return value;
        }
        traceState(key, BOOLEAN_OVERRIDES[(original ? 2 : 0) | (value ? 1 : 0)]);
        return value;
    }

    /**
     * @see #traceOverride(String, boolean, boolean)
     */
    public static int traceOverride(String key, int original, int value) {
        traceNumberOverride(key, original, value, false);
        return value;
    }

    /**
     * @see #traceOverride(String, boolean, boolean)
     */
    public static long traceOverride(String key, long original, long value) {
        traceNumberOverride(key, original, value, false);
        return value;
    }

    /**
     * @see #traceOverride(String, boolean, boolean)
     */
    public static float traceOverride(String key, float original, float value) {
        traceNumberOverride(key, Float.floatToRawIntBits(original), Float.floatToRawIntBits(value), true);
        return value;
    }

    /**
     * @see #traceOverride(String, boolean, boolean)
     */
    @Nullable
    public static String traceOverride(String key, @Nullable String original, @Nullable String value) {
        String[] last = tracedStringOverrides.get(key);
        if (last != null && (Objects.equals(original, value)
                ? Objects.equals(last[0], last[1])
                : Objects.equals(last[0], original) && Objects.equals(last[1], value))) {
            return value;
        }

        tracedStringOverrides.put(key, new String[]{original, value});
        traceState(key, Objects.equals(original, value)
                ? '"' + value + "\" (= app)"
                : '"' + value + "\" (app: \"" + original + "\")");
        return value;
    }

    /**
     * Compares the raw values, so nothing is allocated until a value changes.
     */
    private static void traceNumberOverride(String key, long original, long value, boolean isFloat) {
        long[] last = tracedNumberOverrides.get(key);
        if (last != null && (original == value
                ? last[0] == last[1]
                : last[0] == original && last[1] == value)) {
            return;
        }

        tracedNumberOverrides.put(key, new long[]{original, value});
        String valueString = isFloat ? String.valueOf(Float.intBitsToFloat((int) value)) : String.valueOf(value);
        traceState(key, original == value
                ? valueString + " (= app)"
                : valueString + " (app: " + (isFloat
                ? String.valueOf(Float.intBitsToFloat((int) original))
                : String.valueOf(original)) + ")");
    }

    /**
     * Adds a section to {@link #buildReport(Context)}, such as the A/B flags read since the app started.
     *
     * @param section Builds the text of the section when the report is built.
     */
    public static void addReportSection(LogMessage section) {
        reportSections.add(section);
    }

    /**
     * @return The timeline of this process, oldest first.
     */
    public static Deque<String> getTimeline() {
        return timeline;
    }

    /**
     * Starts recording the activity lifecycle, configuration and memory events and the crashes in the timeline,
     * and keeps the timeline of the previous process. Called when the context is set.
     */
    public static synchronized void startTimeline(Context context) {
        // The first context can be set before the Application is created, and then it has no application context.
        if (timelineStarted || !(context.getApplicationContext() instanceof Application application)) return;
        timelineStarted = true;

        File directory = application.getCacheDir();
        timelineDirectory = directory;
        timelineStartRealtime = SystemClock.elapsedRealtime();
        traceEvent("Process", "Started, pid " + Process.myPid());

        // Renamed before this process saves its own timeline. A rename is a single quick file system call.
        File current = new File(directory, TIMELINE_FILE_NAME);
        if (current.exists() && !current.renameTo(new File(directory, PREVIOUS_TIMELINE_FILE_NAME))) {
            printDebug(() -> "Could not keep the timeline of the previous process");
        }

        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            private String name(Activity activity) {
                return activity.getClass().getSimpleName() + '@'
                        + Integer.toHexString(System.identityHashCode(activity));
            }

            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
                traceEvent("Activity", name(activity) + " created"
                        + (savedInstanceState == null ? "" : " from a saved state")
                        + (activity.getIntent() == null ? "" : ", action: " + activity.getIntent().getAction()
                        + ", data: " + activity.getIntent().getData()));
            }

            @Override
            public void onActivityStarted(@NonNull Activity activity) {
                traceEvent("Activity", name(activity) + " started");
            }

            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                traceEvent("Activity", name(activity) + " resumed");
            }

            @Override
            public void onActivityPaused(@NonNull Activity activity) {
                traceEvent("Activity", name(activity) + " paused");
            }

            @Override
            public void onActivityStopped(@NonNull Activity activity) {
                traceEvent("Activity", name(activity) + " stopped");
                // The app can be killed in the background without any callback.
                List<String> entries = new ArrayList<>(timeline);
                Utils.runOnBackgroundThread(() -> saveTimeline(directory, entries));
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {
                traceEvent("Activity", name(activity) + " destroyed"
                        + (activity.isChangingConfigurations() ? " for a configuration change" : "")
                        + (activity.isFinishing() ? ", finishing" : ""));
            }
        });

        ComponentCallbacks2 componentCallbacks = new ComponentCallbacks2() {
            @Override
            @SuppressWarnings("deprecation")
            public void onConfigurationChanged(@NonNull Configuration configuration) {
                traceState("Orientation", configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                        ? "landscape" : "portrait");
                traceState("Screen", configuration.screenWidthDp + "x" + configuration.screenHeightDp
                        + " dp, smallest width " + configuration.smallestScreenWidthDp + " dp, "
                        + configuration.densityDpi + " dpi, font scale " + configuration.fontScale);
                traceState("NightMode", (configuration.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        == Configuration.UI_MODE_NIGHT_YES);
                traceState("Locale", configuration.locale);
            }

            @Override
            public void onTrimMemory(int level) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
                    Runtime runtime = Runtime.getRuntime();
                    traceEvent("Memory", "Trim level " + level + ", heap "
                            + (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                            + " of " + runtime.maxMemory() / (1024 * 1024) + " MB");
                }
            }

            @Override
            public void onLowMemory() {
                traceEvent("Memory", "Low memory");
            }
        };
        componentCallbacks.onConfigurationChanged(application.getResources().getConfiguration());
        application.registerComponentCallbacks(componentCallbacks);

        Thread.UncaughtExceptionHandler previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                traceEvent("Crash", thread.getName() + ": " + describeThrowable(throwable));
                saveTimeline(directory, new ArrayList<>(timeline));
            } catch (Throwable ignored) {
                // The crash must always reach the previous handler.
            }
            if (previousHandler != null) {
                previousHandler.uncaughtException(thread, throwable);
            }
        });
    }

    /**
     * Lock of the timeline file, which the background threads and a crashing thread save.
     * Not the class lock, which every log call uses.
     */
    private static final Object timelineFileLock = new Object();

    private static void saveTimeline(File directory, List<String> entries) {
        synchronized (timelineFileLock) {
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(new File(directory, TIMELINE_FILE_NAME)), StandardCharsets.UTF_8))) {
                for (String entry : entries) {
                    writer.write(entry);
                    writer.newLine();
                }
            } catch (Exception ex) {
                printDebug(() -> "Could not save the timeline", ex);
            }
        }
    }

    /**
     * @return The value of the setting as it's exported, or only its length if it may be private.
     */
    private static String getExportedValue(Setting<?> setting) {
        String value = String.valueOf(setting.get());
        if (setting instanceof BooleanSetting || setting instanceof EnumSetting<?>
                || (!PRIVATE_SETTING_KEY.matcher(setting.key).find()
                && (value.length() <= REPORT_SETTING_VALUE_MAX_LENGTH || setting.key.contains("feature_flags"))
                && PLAIN_SETTING_VALUE.matcher(value).matches())) {
            return value.replace('\n', ' ');
        }
        return "<" + value.length() + " characters>";
    }

    /**
     * Traces a setting changed while the app runs, such as by the user.
     * Long and string settings are left out, as the patches also use them for counters, times and caches.
     */
    public static void traceSettingChange(Setting<?> setting) {
        if (setting instanceof BooleanSetting || setting instanceof EnumSetting<?>
                || setting instanceof IntegerSetting || setting instanceof FloatSetting) {
            traceEvent("Setting", setting.key + " = " + getExportedValue(setting));
        }
    }

    /**
     * @return The lines exported before the debug logs: app and device, settings changed from their
     *         default, the sections added by the patches, the current states, the timeline of the
     *         previous process and the timeline of this process.
     */
    public static List<String> buildReport(Context context) {
        List<String> lines = new ArrayList<>(TIMELINE_MAX_ENTRIES + PREVIOUS_TIMELINE_MAX_ENTRIES + 300);
        try {
            lines.add("=== Morphe debug report ===");
            long startRealtime = timelineStartRealtime;
            lines.add("Exported: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date())
                    + (startRealtime == 0 ? "" : ", app started "
                    + (SystemClock.elapsedRealtime() - startRealtime) / 1000 + " s ago"));
            lines.add("App: " + Utils.getApplicationName() + ' ' + Utils.getAppVersionName()
                    + " (" + context.getPackageName() + "), patches " + Utils.getPatchesReleaseVersion());
            lines.add("Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + "), device: "
                    + Build.MANUFACTURER + ' ' + Build.MODEL + " (" + Build.DEVICE + "), ABI: "
                    + Build.SUPPORTED_ABIS[0] + (Process.is64Bit() ? " 64 bit" : " 32 bit"));
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            Runtime runtime = Runtime.getRuntime();
            lines.add("Display: " + metrics.widthPixels + 'x' + metrics.heightPixels + " px, "
                    + metrics.densityDpi + " dpi, tablet: " + Utils.isTablet()
                    + ", heap: " + (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                    + " of " + runtime.maxMemory() / (1024 * 1024) + " MB");

            lines.add("=== Settings changed from their default ===");
            List<String> settings = new ArrayList<>();
            for (Setting<?> setting : Setting.allLoadedSettings()) {
                if (setting.isSetToDefault()) continue;

                settings.add(setting.key + " = " + getExportedValue(setting));
            }
            Collections.sort(settings);
            lines.addAll(settings);

            for (LogMessage section : reportSections) {
                try {
                    lines.add(section.buildMessageString());
                } catch (Exception ex) {
                    printException(() -> "Report section failure", ex);
                }
            }

            lines.add("=== Current states ===");
            for (Map.Entry<String, Object> state : new TreeMap<>(tracedStates).entrySet()) {
                lines.add(state.getKey() + ": " + state.getValue());
            }

            File directory = timelineDirectory;
            File previousTimeline = directory == null ? null : new File(directory, PREVIOUS_TIMELINE_FILE_NAME);
            if (previousTimeline != null && previousTimeline.exists()) {
                List<String> previous = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        new FileInputStream(previousTimeline), StandardCharsets.UTF_8))) {
                    for (String line; (line = reader.readLine()) != null; ) {
                        previous.add(line);
                    }
                }
                int start = Math.max(0, previous.size() - PREVIOUS_TIMELINE_MAX_ENTRIES);
                lines.add("=== Timeline of the previous process, saved when it last stopped or crashed (last "
                        + (previous.size() - start) + " of " + previous.size() + " entries) ===");
                lines.addAll(previous.subList(start, previous.size()));
            }

            lines.add("=== Timeline (" + timelineSize.get() + " entries, max " + TIMELINE_MAX_ENTRIES + ") ===");
            lines.addAll(timeline);
            lines.add("=== Logs ===");
        } catch (Exception ex) {
            printException(() -> "buildReport failure", ex);
        }
        return lines;
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

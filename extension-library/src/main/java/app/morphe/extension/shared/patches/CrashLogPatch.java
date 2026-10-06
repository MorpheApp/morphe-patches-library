/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches-library/pull/63
 *
 * File-Specific License Notice (GPLv3 Section 7 Terms)
 *
 * This file is part of the Morphe project and is licensed under
 * the GNU General Public License version 3 (GPLv3), with the Additional
 * Terms under Section 7 described in the LICENSE file.
 *
 * https://www.gnu.org/licenses/gpl-3.0.html
 *
 * Section 7b: Notice Preservation
 * -------------------------------
 * This entire comment block must be preserved in all copies,
 * distributions, and derivative works of this file, in both
 * original and modified source forms.
 *
 * Portions of this software are provided "AS IS" by the Morphe software project.
 * Any express or implied warranties, including the implied warranties of
 * merchantability and fitness for a particular purpose, are disclaimed.
 */

package app.morphe.extension.shared.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;

/**
 * Writes a text file to the device Documents folder when the app crashes.
 * <p>
 * Android 10+ uses MediaStore, which does not require any storage permission.
 * Android 8 and 9 write directly to the public Documents folder,
 * which requires the WRITE_EXTERNAL_STORAGE permission to be granted.
 * If the public folder cannot be written to, the app specific
 * external files folder is used instead (Android/data/[package]/files/Documents).
 */
@SuppressWarnings("unused")
public final class CrashLogPatch {

    private static final String CRASH_LOG_SUB_FOLDER = "Morphe";

    /**
     * Must match the package names added to the manifest queries by the crash log patch.
     */
    private static final String MANAGER_PACKAGE_NAME = "app.morphe.manager";
    private static final String MICROG_PACKAGE_NAME = "app.revanced.android.gms";

    /**
     * How long to keep the crashed process alive so the toast can be seen.
     */
    private static final long TOAST_DISPLAY_MILLISECONDS = 2000;

    private static volatile boolean handlerInstalled;

    /**
     * Prevents recursive crash handling if writing the crash log itself crashes.
     */
    private static volatile boolean handlingCrash;

    /**
     * Injection point.
     */
    public static void setUncaughtExceptionHandler(Activity activity) {
        // Utils.context is not set yet and must use activity for context.
        try {
            // Activity can be recreated, but the handler is process wide.
            if (handlerInstalled) {
                return;
            }
            handlerInstalled = true;

            Context appContext = activity.getApplicationContext();
            Thread.UncaughtExceptionHandler originalHandler = Thread.getDefaultUncaughtExceptionHandler();

            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                try {
                    if (!handlingCrash) {
                        handlingCrash = true;
                        String toastMessage = writeCrashLog(appContext, thread, throwable);
                        showToastAndWait(appContext, toastMessage);
                    }
                } catch (Throwable ex) {
                    Logger.printException(() -> "uncaughtException failure", ex);
                } finally {
                    // Must always be called, otherwise the app can be left frozen instead of closing.
                    if (originalHandler != null) {
                        originalHandler.uncaughtException(thread, throwable);
                    }
                }
            });

            Logger.printDebug(() -> "Crash log handler installed");
        } catch (Exception ex) {
            Logger.printException(() -> "setUncaughtExceptionHandler failure", ex);
        }
    }

    /**
     * @return Toast message describing where the crash log was saved.
     */
    private static String writeCrashLog(Context context, Thread thread, Throwable throwable) throws IOException {
        Date now = new Date();
        String fileTimestamp = formatUtc("yyyy-MM-dd_HH-mm-ss", now);
        String fileName = "crash_" + context.getPackageName() + "_" + fileTimestamp + ".txt";
        byte[] contents = buildCrashLog(context, now, thread, throwable).getBytes(StandardCharsets.UTF_8);

        String savedLocation = null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            savedLocation = writeToMediaStoreDocuments(context, fileName, contents);
        } else if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) {
            File documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            savedLocation = writeToFolder(new File(documents, CRASH_LOG_SUB_FOLDER), fileName, contents);
        }

        if (savedLocation != null) {
            String finalSavedLocation = savedLocation;
            Logger.printInfo(() -> "Crash log saved: " + finalSavedLocation);
            return getString("morphe_crash_log_saved_documents", "Crash log saved to user documents: %s", fileName);
        }

        // Public Documents folder is not available. Use the app specific folder that never needs permissions.
        File appDocuments = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (appDocuments == null) {
            throw new IOException("External storage is not available");
        }
        savedLocation = writeToFolder(appDocuments, fileName, contents);
        if (savedLocation == null) {
            throw new IOException("Could not write crash log");
        }

        String finalSavedLocation = savedLocation;
        Logger.printInfo(() -> "Crash log saved: " + finalSavedLocation);
        return getString("morphe_crash_log_saved", "Crash log saved: %s", savedLocation);
    }

    /**
     * Localized string resources are not present if the patches do not include them,
     * so this falls back to hard coded English instead of logging a missing resource exception.
     *
     * @param key String resource name.
     * @param englishFormat English string format to use if the resource does not exist.
     */
    private static String getString(String key, String englishFormat, Object... args) {
        // Context is not set if the app crashed before the extension hooks ran.
        if (!Utils.isContextSet() || ResourceUtils.getIdentifier(ResourceType.STRING, key) == 0) {
            return String.format(englishFormat, args);
        }

        return str(key, args);
    }

    /**
     * Shows a toast and blocks the calling thread so the toast is visible before the app closes.
     * <p>
     * The toast is shown on a new thread with its own looper, because this is called on the crashed thread:
     * <ul>
     * <li>If the main thread crashed, its looper is no longer running. Toasts posted to the main thread
     *     (such as {@link app.morphe.extension.shared.Utils#showToast(String, int)}) never show,
     *     and Android 10 and lower cannot show a toast directly on the main thread either.
     * <li>If a background thread crashed, it usually has no looper and creating a toast throws an exception.
     * </ul>
     */
    private static void showToastAndWait(Context context, String message) throws InterruptedException {
        Thread toastThread = new Thread(() -> {
            try {
                // A toast requires a looper on the thread that shows it.
                Looper.prepare();
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                Looper.loop();
            } catch (Exception ex) {
                Logger.printException(() -> "showToastAndWait failure", ex);
            }
        }, "Morphe crash log toast");
        toastThread.setDaemon(true);
        toastThread.start();

        // Keep the process alive while the toast is visible, otherwise the original handler closes the app immediately.
        Thread.sleep(TOAST_DISPLAY_MILLISECONDS);
    }

    @Nullable
    @RequiresApi(Build.VERSION_CODES.Q)
    private static String writeToMediaStoreDocuments(Context context, String fileName, byte[] contents) {
        Uri uri = null;
        ContentResolver resolver = context.getContentResolver();
        String relativePath = Environment.DIRECTORY_DOCUMENTS + File.separator + CRASH_LOG_SUB_FOLDER;

        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);

            uri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
            if (uri == null) {
                Logger.printDebug(() -> "MediaStore insert returned null");
                return null;
            }

            try (OutputStream stream = resolver.openOutputStream(uri)) {
                if (stream == null) {
                    throw new IOException("Could not open output stream: " + uri);
                }
                stream.write(contents);
            }

            return relativePath + File.separator + fileName;
        } catch (Exception ex) {
            Logger.printException(() -> "writeToMediaStoreDocuments failure", ex);

            if (uri != null) {
                resolver.delete(uri, null, null);
            }

            return null;
        }
    }

    @Nullable
    private static String writeToFolder(File folder, String fileName, byte[] contents) {
        try {
            if (!folder.isDirectory() && !folder.mkdirs()) {
                throw new IOException("Could not create folder: " + folder);
            }

            File file = new File(folder, fileName);
            try (OutputStream stream = new FileOutputStream(file)) {
                stream.write(contents);
            }

            return file.getAbsolutePath();
        } catch (Exception ex) {
            Logger.printException(() -> "writeToFolder failure: " + folder, ex);
            return null;
        }
    }

    /**
     * Uses UTC so the crash log and file name do not reveal the user's time zone.
     */
    private static String formatUtc(String pattern, Date date) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(date);
    }

    private static String buildCrashLog(Context context, Date time, Thread thread, Throwable throwable) {
        StringWriter stackTrace = new StringWriter();
        throwable.printStackTrace(new PrintWriter(stackTrace));

        String crashLog = "Morphe crash log\n\n"
                + "Time: " + formatUtc("yyyy-MM-dd HH:mm:ss.SSS", time) + " UTC\n"
                + "App: " + context.getPackageName() + " " + getInstalledVersion(context, context.getPackageName()) + "\n"
                + "Patches: " + Utils.getPatchesReleaseVersion() + "\n"
                + "Manager: " + getInstalledVersion(context, MANAGER_PACKAGE_NAME) + "\n"
                + "MicroG: " + getInstalledVersion(context, MICROG_PACKAGE_NAME) + "\n"
                + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")\n"
                + "Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n"
                + "ABIs: " + Arrays.toString(Build.SUPPORTED_ABIS) + "\n"
                + "Thread: " + thread.getName() + "\n"
                + "\nStack trace:\n" + stackTrace;

        String debugLogs = Logger.getFilteredLogs();
        if (!debugLogs.isEmpty()) {
            crashLog += "\nMorphe logs:\n" + debugLogs + "\n";
        }

        return crashLog;
    }

    private static String getInstalledVersion(Context context, String packageName) {
        PackageManager packageManager = context.getPackageManager();
        try {
            return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
                    : packageManager.getPackageInfo(packageName, 0)
            ).versionName;
        } catch (PackageManager.NameNotFoundException ex) {
            return "Not installed";
        }
    }
}

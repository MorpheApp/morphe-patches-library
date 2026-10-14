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

package app.morphe.patches.all.misc.crashlog

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatch
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.all.misc.gms.gmsCorePackageQueriesPatch

private const val EXTENSION_CLASS = "Lapp/morphe/extension/shared/patches/CrashLogPatch;"

/**
 * Must match the package name in the extension CrashLogPatch.
 */
private const val MANAGER_PACKAGE_NAME = "app.morphe.manager"

/**
 * Android 11+ requires declaring other apps in the manifest queries,
 * otherwise they are not visible and their versions cannot be logged.
 */
private val crashLogResourcePatch = resourcePatch {
    // Adds MicroG to the manifest queries.
    dependsOn(gmsCorePackageQueriesPatch)

    execute {
        document("AndroidManifest.xml").use { document ->
            val queries = document.getElementsByTagName("queries").item(0)
                ?: document.createElement("queries").also { document.documentElement.appendChild(it) }

            queries.appendChild(
                document.createElement("package").apply {
                    setAttribute("android:name", MANAGER_PACKAGE_NAME)
                }
            )
        }
    }
}

/**
 * Writes a crash log text file to the device Documents folder when the app crashes.
 *
 * No storage permission changes are made. Android 10+ saves using MediaStore which requires no permissions.
 * Android 9 and lower saves to the public Documents folder only if the app has been granted
 * WRITE_EXTERNAL_STORAGE, otherwise the app specific external files folder is used.
 *
 * Patch uses two hard coded English strings that can optionally be localized if the calling
 * code adds English and localized strings.xml entries:
 *
 * <string name="morphe_crash_log_saved_documents">Crash log saved to user documents: %s</string>
 * <string name="morphe_crash_log_saved">Crash log saved: %s</string>
 *
 * @param mainActivityFingerprint Main activity onCreate method, where the crash handler is installed.
 */
@Suppress("unused")
fun crashLogPatch(
    mainActivityFingerprint: Fingerprint
): BytecodePatch = bytecodePatch(
    description = "Writes a crash log file to the Documents folder when the app crashes."
) {
    dependsOn(crashLogResourcePatch)

    finalize { // Run last, before any other hooks can run.
        mainActivityFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->setUncaughtExceptionHandler(Landroid/app/Activity;)V"
        )
    }
}

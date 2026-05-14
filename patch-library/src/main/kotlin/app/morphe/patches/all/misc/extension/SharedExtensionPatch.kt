/*
 * Copyright 2025 Morphe.
 * https://github.com/MorpheApp/morphe-patches-library
 *
 * Original code hard forked from:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
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
 * 7c. Project Name Restriction
 * ----------------------------
 * The project name "Morphe" is a protected identifier. Derivative works
 * must adopt a completely different identity that is not related to,
 * confusingly similar to, or an imitation of the name "Morphe."
 */

package app.morphe.patches.all.misc.extension

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.iface.Method
import java.net.URLDecoder
import java.util.jar.JarFile

const val SHARED_UTILS_EXTENSION_CLASS = "Lapp/morphe/extension/shared/Utils;"

/**
 * A patch to extend with an extension shared with multiple patches.
 *
 * @param extensionName The name of the extension to extend with.
 */
fun sharedExtensionPatch(
    extensionName: String,
    vararg hooks: ExtensionHook,
) = sharedExtensionPatch(listOf(extensionName), *hooks)


/**
 * A patch to extend with an extension shared with multiple patches.
 *
 * @param extensionNames The name of the extensions to extend with.
 */
fun sharedExtensionPatch(
    extensionNames: List<String>,
    vararg hooks: ExtensionHook,
) = bytecodePatch(
    default = false
) {
    dependsOn(
        sharedExtensionPatch(*hooks),
        *extensionNames.filter { it != "shared" }.map { sharedExtensionPatch(it, emptyList()) }.toTypedArray(),
    )
}

/**
 * A patch to extend with the "shared" extension.
 *
 * @param hooks The hooks to get the application context for use in the extension,
 * commonly for the onCreate method of exported activities.
 */
fun sharedExtensionPatch(
    vararg hooks: ExtensionHook,
) = sharedExtensionPatch("shared", hooks.toList())

private fun sharedExtensionPatch(
    extensionName: String,
    hooks: List<ExtensionHook>,
) = bytecodePatch(
    default = false
) {
    extendWith("extensions/$extensionName.mpe")

    execute {
        // Verify the extension class exists.
        classDefBy(SHARED_UTILS_EXTENSION_CLASS)
    }

    finalize {
        // The hooks are made in finalize to ensure that the context is hooked before any other patches.
        hooks.forEach { hook -> hook(SHARED_UTILS_EXTENSION_CLASS) }

        // Modify Utils method to include the patches release version.
        MorpheUtilsPatchesVersionFingerprint.method.apply {
            /**
             * @return The file path for the jar this classfile is contained inside.
             */
            fun getCurrentJarFilePath(): String {
                val className = object {}::class.java.enclosingClass.name.replace('.', '/') + ".class"
                val classUrl = object {}::class.java.classLoader?.getResource(className)
                if (classUrl != null) {
                    val urlString = classUrl.toString()

                    if (urlString.startsWith("jar:file:")) {
                        val end = urlString.lastIndexOf('!')

                        return URLDecoder.decode(urlString.substring("jar:file:".length, end), "UTF-8")
                    }
                }
                throw IllegalStateException("Not running from inside a JAR file.")
            }

            /**
             * @return The value for the manifest entry,
             *         or "Unknown" if the entry does not exist or is blank.
             */
            @Suppress("SameParameterValue")
            fun getPatchesManifestEntry(attributeKey: String) = JarFile(getCurrentJarFilePath()).use { jarFile ->
                jarFile.manifest.mainAttributes.entries.firstOrNull { it.key.toString() == attributeKey }?.value?.toString()
                    ?: "Unknown"
            }

            val manifestValue = getPatchesManifestEntry("Version")
            returnEarly(manifestValue)
        }
    }
}

/**
 * Handles passing the application context to the extension code. Typically, the main activity
 * onCreate() method is hooked, but sometimes additional hooks are required if extension code
 * can be reached before the main activity is fully created.
 */
open class ExtensionHook(
    val fingerprint: Fingerprint,
    private val insertIndexResolver: BytecodePatchContext.(Method) -> Int = { 0 },
    private val contextRegisterResolver: BytecodePatchContext.(Method) -> String = { "v${it.implementation!!.registerCount - it.parameters.size - 1}" },
) {
    context(patchContext: BytecodePatchContext)
    operator fun invoke(extensionClassDescriptor: String) {
        fingerprint.method.apply {
            val insertIndex = patchContext.insertIndexResolver(this)
            val contextRegister = patchContext.contextRegisterResolver(this)

            addInstruction(
                insertIndex,
                "invoke-static/range { $contextRegister .. $contextRegister }, " +
                        "$extensionClassDescriptor->setContext(Landroid/content/Context;)V",
            )
        }
    }
}

/**
 * Creates an extension hook from a non-obfuscated activity, which typically is the main activity
 * defined in the app manifest.xml file.
 *
 * @param activityClassType Either the full activity class type such as `Lcom/company/MainActivity;`
 *                          or the 'ends with' string for the activity such as `/MainActivity;`
 */
fun activityOnCreateExtensionHook(activityClassType: String): ExtensionHook {
    require(activityClassType.endsWith(';')) {
        "Class type must end with a semicolon: $activityClassType"
    }

    val fingerprint = Fingerprint(
        definingClass = activityClassType,
        name = "onCreate",
        returnType = "V",
    )

    return ExtensionHook(fingerprint)
}

/**
 * Creates an extension hook from a non-obfuscated activity, which typically is the main activity
 * defined in the app manifest.xml file.
 *
 * @param activityClassType Either the full activity class type such as `Lcom/company/MainActivity;`
 *                          or the 'ends with' string for the activity such as `/MainActivity;`
 * @param targetBundleMethod If the extension should hook `onCreate(Landroid/os/Bundle;)` or `onCreate()`
 */
fun activityOnCreateExtensionHook(activityClassType: String = "/MainActivity;", targetBundleMethod: Boolean = true) =
    ExtensionHook(getMainOnCreateFingerprint(activityClassType, targetBundleMethod))

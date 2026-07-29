/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches-library
 *
 * See the included NOTICE file for §7(c) terms that apply to this code.
 */

package app.morphe.patches.all.misc.fix.changepackageinstaller

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.matchAllMethodIndicesForEach
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/**
 * App store source.
 */
private const val PACKAGE_INSTALLER_PACKAGE_SOURCE_STORE = 2

/**
 * Spoofs the installer source for all methods/field usage of:
 *
 * ```
 *  Landroid/content/pm/PackageManager;->getInstallerPackageName(Ljava/lang/String;)Ljava/lang/String;
 *  Landroid/content/pm/InstallSourceInfo;->getInstallingPackageName()Ljava/lang/String;
 *  Landroid/content/pm/InstallSourceInfo;->getInitiatingPackageName()Ljava/lang/String;
 *  Landroid/content/pm/InstallSourceInfo;->getPackageSource()I
 * ```
 *
 * @param installerPackageName Installer package name to use. Defaults to the Google Play Store.
 */
@Suppress("unused")
fun changePackageInstallerPatch(installerPackageName : String = "com.android.vending") = bytecodePatch {
    execute {
        arrayOf(
            "Landroid/content/pm/PackageManager;->getInstallerPackageName(Ljava/lang/String;)Ljava/lang/String;",
            "Landroid/content/pm/InstallSourceInfo;->getInstallingPackageName()Ljava/lang/String;",
            "Landroid/content/pm/InstallSourceInfo;->getInitiatingPackageName()Ljava/lang/String;",
            "Landroid/content/pm/InstallSourceInfo;->getPackageSource()I"
        ).forEach { smali ->
            val methodCall = methodCall(smali)
            val isIntReturnType = methodCall.returnType == "I"
            val expectedReturnOpcode = if (isIntReturnType) {
                Opcode.MOVE_RESULT
            } else {
                Opcode.MOVE_RESULT_OBJECT
            }

            methodCall.matchAllMethodIndicesForEach(requireMatches = false) { index ->
                val returnIndex = index + 1
                val instruction = getInstruction(returnIndex)

                if (instruction.opcode != expectedReturnOpcode) {
                    return@matchAllMethodIndicesForEach
                }

                val register = (instruction as OneRegisterInstruction).registerA
                val smaliInstruction = if (isIntReturnType) {
                    "const/4 v$register, $PACKAGE_INSTALLER_PACKAGE_SOURCE_STORE"
                } else {
                    "const-string v$register, \"$installerPackageName\""
                }
                replaceInstruction(
                    returnIndex,
                    smaliInstruction
                )
            }
        }
    }
}


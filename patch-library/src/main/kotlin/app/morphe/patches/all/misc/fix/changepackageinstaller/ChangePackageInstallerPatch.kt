/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches-library
 *
 * See the included NOTICE file for §7(c) terms that apply to this code.
 */

package app.morphe.patches.all.misc.fix.changepackageinstaller

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import app.morphe.util.iterateInstructionsUsingFilter
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

/**
 * Spoofs the installer source for all methods/field usage of:
 *
 * ```
 *  Landroid/content/pm/PackageInfo;->packageName:Ljava/lang/String;
 *  Landroid/content/pm/PackageManager;->getInstallerPackageName(Ljava/lang/String;)Ljava/lang/String;
 *  Landroid/content/pm/InstallSourceInfo;->getInstallingPackageName()Ljava/lang/String;
 *  Landroid/content/pm/InstallSourceInfo;->getOriginatingPackageName()Ljava/lang/String;
 *  Landroid/content/pm/InstallSourceInfo;->getInitiatingPackageName()Ljava/lang/String;
 * ```
 *
 * @param installerPackageName Installer package name to use. Defaults to the Google Play Store.
 */
fun changePackageInstallerPatch(installerPackageName : String = "com.android.vending") = bytecodePatch {
    execute {
        arrayOf(
            "Landroid/content/pm/PackageInfo;->packageName:Ljava/lang/String;",

            "Landroid/content/pm/PackageManager;->getInstallerPackageName(Ljava/lang/String;)Ljava/lang/String;",
            "Landroid/content/pm/InstallSourceInfo;->getInstallingPackageName()Ljava/lang/String;",
            "Landroid/content/pm/InstallSourceInfo;->getOriginatingPackageName()Ljava/lang/String;",
            "Landroid/content/pm/InstallSourceInfo;->getInitiatingPackageName()Ljava/lang/String;"
        ).forEach { smali ->
            iterateInstructionsUsingFilter(
                if (smali.contains(':')) fieldAccess(smali) else methodCall(smali)
            ) { index ->
                val returnIndex = index + 1
                val instruction = getInstruction(returnIndex)
                if (instruction.opcode != Opcode.MOVE_RESULT_OBJECT) {
                    return@iterateInstructionsUsingFilter
                }

                val register = (instruction as OneRegisterInstruction).registerA
                replaceInstruction(
                    returnIndex,
                    "const-string v$register, \"$installerPackageName\""
                )

                println("replaced: " + getInstruction(index).getReference())
            }
        }
    }
}


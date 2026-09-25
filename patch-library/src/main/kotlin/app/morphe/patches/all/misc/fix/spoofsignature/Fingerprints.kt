/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches-library
 *
 * See the included NOTICE file for §7(c) terms that apply to this code.
 */

package app.morphe.patches.all.misc.fix.spoofsignature

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

object GetPackageInfoFingerprint : Fingerprint (
    filters = listOf(
        methodCall(
            name = "getPackageInfo",
            definingClass = "Landroid/content/pm/PackageManager;",
            returnType = "Landroid/content/pm/PackageInfo;",
            opcode = Opcode.INVOKE_VIRTUAL
        )
    ),
    custom = { _, classDef ->
        !classDef.type.startsWith("Lapp/morphe/extension")
    }
)


object SignatureSpoofCtorFingerprint : Fingerprint (
    definingClass = Constants.SPOOF_CLASS_SMALI_NAME,
    name = "<clinit>",
    filters = listOf(
        string("PACKAGE_NAME_PLACEHOLDER"),
        string("SIGNATURE_PLACEHOLDER")
    )
)
/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches-library/pull/61
 *
 * See the included NOTICE file for §7(c) terms that apply to this code.
 */

package app.morphe.patches.all.misc.fix.spoofsignature

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.encodedValue.MutableStringEncodedValue
import app.morphe.patches.all.misc.fix.spoofsignature.Constants.SPOOF_CLASS_SMALI_NAME
import app.morphe.util.fiveRegisters
import app.morphe.util.getEndEntityCertificate
import app.morphe.util.getReference
import app.morphe.util.matchAllMethodIndicesForEach
import app.morphe.util.writeRegister
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.util.Base64

private lateinit var packageName: String
private lateinit var signature: String

private val getMetadataPatch = resourcePatch {
    execute {
        val cert = getEndEntityCertificate(packageMetadata.signingCertificates)

        if (isCertMaybeInauthentic(cert)) throw PatchException("Invalid signing certificate. Original APK is required.")

        signature = Base64.getEncoder().encodeToString(cert.encoded)
        packageName = packageMetadata.packageName
    }
}

@Suppress("unused")
val spoofSignaturePatch = bytecodePatch {
    dependsOn(getMetadataPatch)

    extendWith("generated/extensions/signature.dex")

    execute {
        GetPackageInfoFingerprint.matchAllMethodIndicesForEach(false) { index ->
            val registers = fiveRegisters(index)
            val instr = getInstruction<FiveRegisterInstruction>(index)
            val params = "Landroid/content/pm/PackageManager;" +
                    instr.getReference<MethodReference>()!!.parameterTypes.joinToString("")

            replaceInstruction(
                index,
                "invoke-static { $registers }, $SPOOF_CLASS_SMALI_NAME->getPackageInfo($params)Landroid/content/pm/PackageInfo;"
            )
        }
    }

    finalize {
        // Depending on how d8 optimizes, the static fields are either set with an initialValue
        // or hydrated in the static constructor. So we'll handle both cases just to be safe.
        mutableClassDefBy(SPOOF_CLASS_SMALI_NAME).apply {
            (staticFields.first { it.name == "PACKAGE_NAME" }.initialValue as? MutableStringEncodedValue)?.value = packageName
            (staticFields.first { it.name == "SIGNATURE" }.initialValue as? MutableStringEncodedValue)?.value = signature

            SignatureSpoofCtorFingerprint.apply {
                instructionMatchesOrNull?.first()?.also { match ->
                    method.replaceInstruction(
                        match.index,
                        "const-string v${match.instruction.writeRegister}, \"$packageName\""
                    )
                }
                instructionMatchesOrNull?.last()?.also { match ->
                    method.replaceInstruction(
                        match.index,
                        "const-string v${match.instruction.writeRegister}, \"$signature\""
                    )
                }
            }
        }
    }
}

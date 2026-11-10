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
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.packageMetadata
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.matchAllMethodIndicesForEach
import app.morphe.patcher.util.proxy.mutableTypes.encodedValue.MutableStringEncodedValue
import app.morphe.patcher.util.writeRegister
import app.morphe.patches.all.misc.fix.spoofsignature.Constants.SPOOF_CLASS_SMALI_NAME
import app.morphe.util.fiveRegisters
import app.morphe.util.getEndEntityCertificate
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.util.Base64

/**
 * Manifest metadata with the original signing certificate, so other apps patched with the same
 * key can report it. Must match ORIGINAL_SIGNATURE_METADATA of the extension.
 */
private const val ORIGINAL_SIGNATURE_METADATA = "app.morphe.ORIGINAL_SIGNATURE"

private lateinit var packageName: String
private lateinit var signature: String

private val getMetadataPatch = patch {
    execute {
        val cert = getEndEntityCertificate(packageMetadata.signingCertificates)

        // Failing the patch here is intentional. An APK that was already modified and re-signed
        // no longer carries the original certificate, so spoofing would report the wrong one and
        // the app's signature checks would still fail. Users must patch an unmodified original APK.
        if (isCertMaybeInauthentic(cert)) throw PatchException("Invalid signing certificate. Original APK is required.")

        signature = Base64.getEncoder().encodeToString(cert.encoded)
        packageName = packageMetadata.packageName
    }
}

@Suppress("unused")
val spoofSignaturePatch = patch {
    dependsOn(getMetadataPatch)

    extendWith("generated/extensions/signature.dex")

    execute {
        // Publish the original certificate in the manifest. This looks unusual, but it lets other
        // apps patched with this patch report the original certificate of this app, so apps of the
        // same developer keep trusting each other after re-signing. A certificate is public data,
        // and the extension only uses it if this app is signed with the same key as the app
        // reading it, which only the user can do.
        resourceXml("AndroidManifest.xml").use { manifest ->
            manifest.elements("application").first().appendChild("meta-data") {
                setAttribute("android:name", ORIGINAL_SIGNATURE_METADATA)
                setAttribute("android:value", signature)
            }
        }

        listOf(GetPackageInfoFingerprint, GetInstalledPackagesFingerprint).forEach { fingerprint ->
            fingerprint.matchAllMethodIndicesForEach(false) { index ->
                val registers = fiveRegisters(index)
                val reference = getInstruction<FiveRegisterInstruction>(index).getReference<MethodReference>()!!
                val params = "Landroid/content/pm/PackageManager;" + reference.parameterTypes.joinToString("")

                replaceInstruction(
                    index,
                    "invoke-static { $registers }, $SPOOF_CLASS_SMALI_NAME->${reference.name}($params)${reference.returnType}"
                )
            }
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

package app.morphe.patches.all.misc.extension

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal object MorpheUtilsPatchesVersionFingerprint : Fingerprint(
    definingClass = SHARED_UTILS_EXTENSION_CLASS,
    name = "getPatchesReleaseVersion",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
)

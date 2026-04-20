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

internal fun getMainOnCreateFingerprint(activityClassType: String, targetBundleMethod: Boolean = true): Fingerprint {
    require(activityClassType.endsWith(';')) {
        "Class type must end with a semicolon: $activityClassType"
    }

    val fullClassType = activityClassType.startsWith('L')

    return Fingerprint(
        returnType = "V",
        parameters = if (targetBundleMethod) {
            listOf("Landroid/os/Bundle;")
        } else {
            listOf()
        },
        custom = { method, classDef ->
            method.name == "onCreate" &&
                    if (fullClassType) classDef.type == activityClassType
                    else classDef.type.endsWith(activityClassType)
        }
    )
}
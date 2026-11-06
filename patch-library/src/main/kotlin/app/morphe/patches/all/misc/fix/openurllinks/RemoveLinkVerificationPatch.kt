package app.morphe.patches.all.misc.fix.openurllinks

import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.resourceXml

/**
 * Removes `autoVerify` from all intents. This fixes 'open link with' that are impossible
 * to manually enable on some devices.
 */
@Suppress("unused")
val removeLinkVerification = patch {
    execute {
        resourceXml("AndroidManifest.xml").use { manifest ->
            manifest.elements("intent-filter").forEach { intentFilter ->
                intentFilter.removeAttribute("android:autoVerify")
            }
        }
    }
}

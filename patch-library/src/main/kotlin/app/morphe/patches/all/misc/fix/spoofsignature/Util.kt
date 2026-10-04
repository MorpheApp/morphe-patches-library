/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches-library
 *
 * See the included NOTICE file for §7(c) terms that apply to this code.
 */

package app.morphe.patches.all.misc.fix.spoofsignature

import java.security.MessageDigest
import java.security.cert.X509Certificate

fun isCertMaybeInauthentic(cert: X509Certificate): Boolean {
    if (cert.subjectX500Principal.name.contains("morphe", true))
        return true

    val digest = MessageDigest.getInstance("SHA-1").digest(cert.encoded)
    return isKnownSignature(digest.joinToString("") { "%02x".format(it) })
}

private val KNOWN_SHA1 = listOf(
    "e94e3afa40a54ecee4eef83f580393507fcd205a", // AntiSplit M
    "61ed377e85d386a8dfee6b864bd85b0bfaa5af81", // public debug certificate
)
private fun isKnownSignature(certSHA1: String) = KNOWN_SHA1.contains(certSHA1.lowercase())
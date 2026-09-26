/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches-library
 *
 * See the included NOTICE file for §7(c) terms that apply to this code.
 */

package app.morphe.util

import app.morphe.patcher.apk.ApkSignatureScheme
import java.security.cert.X509Certificate

class NoCertificateException : Exception("Unable to extract certificate from apk")

private fun sortOrder(scheme: ApkSignatureScheme) = when (scheme) {
    ApkSignatureScheme.V31 -> 3
    ApkSignatureScheme.V3 -> 2
    ApkSignatureScheme.V2 -> 1
    else -> -1
}

/**
 * Get the leaf certificate from the scheme to certificates map.
 */
fun getEndEntityCertificate(
    schemeToCertsMap: Map<ApkSignatureScheme, List<X509Certificate>>
): X509Certificate {
    // scheme map can have empty lists for some reason
    val filteredMap = schemeToCertsMap.filterValues { it.isNotEmpty() }

    val highestSchemeVersion = filteredMap.keys.maxByOrNull { sortOrder(it) } ?: throw NoCertificateException()
    val certsForScheme = filteredMap[highestSchemeVersion] ?: throw NoCertificateException()

    if (certsForScheme.isEmpty()) throw NoCertificateException()

    // if single/self-signed, it is the developer cert
    if (certsForScheme.size == 1) {
        return certsForScheme.first()
    }

    // if a cert chain exists, find the leaf
    val issuerPrincipals = certsForScheme.map { it.issuerX500Principal }.toSet()
    return certsForScheme.firstOrNull { cert ->
        !issuerPrincipals.contains(cert.subjectX500Principal)
    } ?: certsForScheme.first()
}
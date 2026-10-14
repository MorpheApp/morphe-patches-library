/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches-library/pull/63
 *
 * File-Specific License Notice (GPLv3 Section 7 Terms)
 *
 * This file is part of the Morphe project and is licensed under
 * the GNU General Public License version 3 (GPLv3), with the Additional
 * Terms under Section 7 described in the LICENSE file.
 *
 * https://www.gnu.org/licenses/gpl-3.0.html
 *
 * Section 7b: Notice Preservation
 * -------------------------------
 * This entire comment block must be preserved in all copies,
 * distributions, and derivative works of this file, in both
 * original and modified source forms.
 *
 * Portions of this software are provided "AS IS" by the Morphe software project.
 * Any express or implied warranties, including the implied warranties of
 * merchantability and fitness for a particular purpose, are disclaimed.
 */

package app.morphe.patches.all.misc.gms

import app.morphe.patcher.patch.resourcePatch

/**
 * Makes every known MicroG / GMS variant visible to the package manager,
 * so the extension can detect conflicting installs and log the installed version.
 *
 * Shared by multiple patches so the manifest queries are only added once.
 */
@Suppress("unused")
val gmsCorePackageQueriesPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { document ->
            val queries = document.getElementsByTagName("queries").item(0)
                ?: document.createElement("queries").also { document.documentElement.appendChild(it) }

            listOf(
                "app.revanced.android.gms",
                "com.mgoogle.android.gms",
                "org.microg.gms"
            ).forEach { packageName ->
                queries.appendChild(
                    document.createElement("package").apply {
                        setAttribute("android:name", packageName)
                    }
                )
            }
        }
    }
}

/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
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

package app.morphe.patches.all.misc.string

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.patch
import app.morphe.patcher.patch.replaceStrings
import app.morphe.patcher.string

/**
 * @param from Original string to replace.
 * @param to Replacement string or replacement substring when not using [StringComparisonType.EQUALS].
 * @param requireMatches If true and no matches exist, an exception is thrown.
 * @param comparison String comparison type. Defaults to [StringComparisonType.EQUALS].
 */
@Deprecated(
    "This function may be deleted in the future. Instead call replaceStrings() in the execute block " +
            "of a patch, which replaces many strings with a single search."
)
@Suppress("unused")
fun replaceStringPatch(
    from: String,
    to: String,
    requireMatches: Boolean = false,
    comparison: StringComparisonType = StringComparisonType.EQUALS,
) = patch(
    description = "Replaces occurrences of '$from' with '$to' in String constants."
) {
    execute {
        val filter = string(from, comparison)

        val matchCount = if (from == to) {
            // Replacing a string with itself changes nothing, so the matches are only counted.
            var count = 0
            replaceStrings(filter) { _, _, _ ->
                count++
                null
            }
            count
        } else {
            // Every matching string is changed, so each match is replaced.
            replaceStrings(filter to to)
        }

        if (requireMatches && matchCount == 0) {
            throw PatchException("Could not find any matches of '$from'")
        }
    }
}

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
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.matchAllMethodIndicesForEach
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference

/**
 * @param from Original string to replace.
 * @param to Replacement string or replacement substring when not using [StringComparisonType.EQUALS].
 * @param requireMatches If true and no matches exist, an exception is thrown.
 * @param comparison String comparison type. Defaults to [StringComparisonType.EQUALS].
 */
@Suppress("unused")
fun replaceStringPatch(
    from: String,
    to: String,
    requireMatches: Boolean = false,
    comparison: StringComparisonType = StringComparisonType.EQUALS,
) = bytecodePatch(
    description = "Replaces occurrences of '$from' with '$to' in String constants."
) {
    execute {
        string(from, comparison).matchAllMethodIndicesForEach(requireMatches = requireMatches) { index ->
            val replacement = when (comparison) {
                StringComparisonType.EQUALS -> to
                else -> {
                    getInstruction<ReferenceInstruction>(index)
                        .getReference<StringReference>()!!.string
                        .replace(from, to)
                }
            }

            replaceInstruction(
                index,
                BuilderInstruction21c(
                    Opcode.CONST_STRING,
                    getInstruction<OneRegisterInstruction>(index).registerA,
                    ImmutableStringReference(replacement),
                )
            )
        }
    }
}

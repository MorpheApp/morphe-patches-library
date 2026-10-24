/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches-library
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

package app.morphe.util

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import kotlin.test.Test
import kotlin.test.assertEquals

class InsertLiteralOverrideTest {

    private val extensionMethod = "LExtension;->override(Z)Z"

    /**
     * Static method with the given smali.
     */
    private fun method(registers: Int, smali: String) = ImmutableMethod(
        "LTest;",
        "test",
        listOf(),
        "V",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(registers),
    ).toMutable().apply {
        addInstructions(0, smali)
    }

    private fun MutableMethod.overrideIndices() = instructions.withIndex()
        .filter { (_, instruction) -> instruction.getReference<MethodReference>()?.toString() == extensionMethod }
        .map { it.index }

    @Test
    fun `overrides every read of the literal`() {
        val method = method(
            6,
            """
                const-wide/32 v2, 0x2b4c1d7
                invoke-static { v2, v3 }, LTest;->flag(J)Z
                move-result v0
                const-wide/16 v4, 0x0
                invoke-static { v2, v3 }, LTest;->flag(J)Z
                move-result v0
                return-void
            """
        )

        method.insertLiteralOverride(0, extensionMethod)

        assertEquals(listOf(3, 8), method.overrideIndices())
    }

    @Test
    fun `does not override a read of a long written over the literal`() {
        // The second long is written to v1 and v2, so the second read is of another flag.
        val method = method(
            6,
            """
                const-wide/32 v2, 0x2b4c1d7
                invoke-static { v2, v3 }, LTest;->flag(J)Z
                move-result v0
                const-wide/32 v1, 0x2b91fd4
                invoke-static { v1, v2 }, LTest;->flag(J)Z
                move-result v0
                return-void
            """
        )

        method.insertLiteralOverride(0, extensionMethod)

        assertEquals(listOf(3), method.overrideIndices())
    }
}

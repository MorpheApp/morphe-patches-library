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

import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.writer.builder.DexBuilder
import com.android.tools.smali.smali.smaliFlexLexer
import com.android.tools.smali.smali.smaliParser
import com.android.tools.smali.smali.smaliTreeWalker
import org.antlr.runtime.CommonTokenStream
import org.antlr.runtime.tree.CommonTreeNodeStream
import java.io.StringReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FreeRegisterProviderTest {

    /**
     * Assembles a static method from smali so tests can describe bytecode directly.
     */
    private fun method(
        registers: Int,
        body: String,
        parameters: String = "",
        returnType: String = "V",
    ): MutableMethod {
        val smali = """
            .class public LTest;
            .super Ljava/lang/Object;
            .field public static wide:J
            .field public static array:[I
            .method public static test($parameters)$returnType
                .registers $registers
                $body
            .end method
        """.trimIndent()

        val lexer = smaliFlexLexer(StringReader(smali), 34)
        val tokens = CommonTokenStream(lexer)
        val parser = smaliParser(tokens)
        val tree = parser.smali_file().tree
        check(lexer.numberOfSyntaxErrors == 0 && parser.numberOfSyntaxErrors == 0) {
            "Invalid test smali:\n$smali"
        }

        val nodes = CommonTreeNodeStream(tree)
        nodes.tokenStream = tokens
        val walker = smaliTreeWalker(nodes)
        walker.setDexBuilder(DexBuilder(Opcodes.getDefault()))

        return walker.smali_file().methods.first().toMutable()
    }

    @Test
    fun `finds the register written by a plain move`() {
        val method = method(
            registers = 2,
            returnType = "I",
            body = """
                const/4 v0, 0x1
                move v1, v0
                return v1
            """
        )

        assertEquals(1, method.findFreeRegister(1))
    }

    @Test
    fun `follows a branch that is the first instruction`() {
        val method = method(
            registers = 2,
            parameters = "I",
            body = """
                if-eqz p0, :skip
                const/4 v0, 0x1
                :skip
                return-void
            """
        )

        assertEquals(0, method.findFreeRegister(0))
    }

    @Test
    fun `does not use the destination of a 2addr instruction`() {
        val method = method(
            registers = 3,
            returnType = "I",
            body = """
                const/4 v0, 0x1
                const/4 v1, 0x2
                add-int/2addr v0, v1
                return v0
            """
        )

        assertEquals(2, method.findFreeRegister(2))
    }

    @Test
    fun `every 2addr opcode is a read-modify-write opcode`() {
        Opcode.entries.filter { it.name.endsWith("/2addr") }.forEach { opcode ->
            assertTrue(opcode in FreeRegisterProvider.readModifyWriteOpcodes, "Missing: $opcode")
        }
    }

    @Test
    fun `finds the register written by a compare`() {
        val method = method(
            registers = 5,
            returnType = "I",
            body = """
                const-wide v0, 0x1
                const-wide v2, 0x2
                cmp-long v4, v0, v2
                return v4
            """
        )

        assertEquals(4, method.findFreeRegister(2))
    }

    @Test
    fun `write opcodes match the opcodes dexlib2 declares as setting a register`() {
        // check-cast writes back the same object it reads, so it never frees a register.
        val notWriteOnly = setOf(Opcode.CHECK_CAST)

        Opcode.entries.filter { !it.odexOnly() && it !in notWriteOnly }.forEach { opcode ->
            assertEquals(
                opcode.setsRegister(),
                opcode in FreeRegisterProvider.writeOpcodes,
                "Mismatch: $opcode"
            )
        }
    }
}

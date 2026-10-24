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

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.writer.builder.DexBuilder
import com.android.tools.smali.smali.smaliFlexLexer
import com.android.tools.smali.smali.smaliParser
import com.android.tools.smali.smali.smaliTreeWalker
import org.antlr.runtime.CommonTokenStream
import org.antlr.runtime.tree.CommonTreeNodeStream
import java.io.StringReader
import kotlin.test.assertEquals

class AddFreeRegistersTest {

    /**
     * Assembles a class with a single method from smali.
     */
    private fun mutableClass(
        registers: Int,
        body: String,
        modifiers: String = "public",
        parameters: String = "",
        returnType: String = "V",
    ): MutableClass {
        val smali = """
            .class public LTest;
            .super Ljava/lang/Object;
            .method $modifiers test($parameters)$returnType
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

        return MutableClass(walker.smali_file())
    }

    private fun MutableMethod.assertMove(index: Int, opcode: Opcode, destination: Int, source: Int) {
        val instruction = getInstruction<TwoRegisterInstruction>(index)
        assertEquals(opcode, instruction.opcode)
        assertEquals(destination, instruction.registerA)
        assertEquals(source, instruction.registerB)
    }
}

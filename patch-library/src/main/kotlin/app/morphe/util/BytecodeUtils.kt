/*
 * Copyright 2025 Morphe.
 * https://github.com/MorpheApp/morphe-patches-library
 *
 * Original code hard forked from:
 * https://github.com/ReVanced/revanced-patches/blob/724e6d61b2ecd868c1a9a37d465a688e83a74799/patches/src/main/kotlin/app/revanced/util/BytecodeUtils.kt
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

@file:Suppress("unused", "SpellCheckingInspection")

package app.morphe.util

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionFilter
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.literal
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.resource.ResourceType
import app.morphe.patcher.resource.resourceId
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.numberOfParameterRegistersLogical
import app.morphe.patcher.util.p0Register
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableField
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.registersUsed
import app.morphe.patcher.util.returnEarly
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patcher.util.writeRegister
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcode.GOTO
import com.android.tools.smali.dexlib2.Opcode.GOTO_16
import com.android.tools.smali.dexlib2.Opcode.GOTO_32
import com.android.tools.smali.dexlib2.Opcode.IF_EQ
import com.android.tools.smali.dexlib2.Opcode.IF_EQZ
import com.android.tools.smali.dexlib2.Opcode.IF_GE
import com.android.tools.smali.dexlib2.Opcode.IF_GEZ
import com.android.tools.smali.dexlib2.Opcode.IF_GT
import com.android.tools.smali.dexlib2.Opcode.IF_GTZ
import com.android.tools.smali.dexlib2.Opcode.IF_LE
import com.android.tools.smali.dexlib2.Opcode.IF_LEZ
import com.android.tools.smali.dexlib2.Opcode.IF_LT
import com.android.tools.smali.dexlib2.Opcode.IF_LTZ
import com.android.tools.smali.dexlib2.Opcode.IF_NE
import com.android.tools.smali.dexlib2.Opcode.IF_NEZ
import com.android.tools.smali.dexlib2.Opcode.MOVE_RESULT
import com.android.tools.smali.dexlib2.Opcode.MOVE_RESULT_OBJECT
import com.android.tools.smali.dexlib2.Opcode.MOVE_RESULT_WIDE
import com.android.tools.smali.dexlib2.Opcode.PACKED_SWITCH
import com.android.tools.smali.dexlib2.Opcode.RETURN
import com.android.tools.smali.dexlib2.Opcode.RETURN_OBJECT
import com.android.tools.smali.dexlib2.Opcode.RETURN_VOID
import com.android.tools.smali.dexlib2.Opcode.RETURN_VOID_NO_BARRIER
import com.android.tools.smali.dexlib2.Opcode.RETURN_WIDE
import com.android.tools.smali.dexlib2.Opcode.SPARSE_SWITCH
import com.android.tools.smali.dexlib2.Opcode.THROW
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.MethodParameter
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.Reference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.util.MethodUtil
import java.util.EnumSet

/**
 * Find the instruction index used for a toString() StringBuilder write of a given String name.
 *
 * @param fieldName The name of the field to find. Partial matches are allowed.
 */
private fun Method.findInstructionIndexFromToString(fieldName: String, isField: Boolean) : Int {
    val stringIndex = indexOfFirstInstruction {
        val reference = getReference<StringReference>()
        reference?.string?.contains(fieldName) == true
    }
    if (stringIndex < 0) {
        throw IllegalArgumentException("Could not find usage of string: '$fieldName'")
    }
    val stringRegister = getInstruction<OneRegisterInstruction>(stringIndex).registerA

    // Find use of the string with a StringBuilder.
    val stringUsageIndex = indexOfFirstInstruction(stringIndex) {
        val reference = getReference<MethodReference>()
        reference?.definingClass == "Ljava/lang/StringBuilder;" &&
                registersUsed.getOrNull(1) == stringRegister
    }
    if (stringUsageIndex < 0) {
        throw IllegalArgumentException("Could not find StringBuilder usage in: $this")
    }

    // Find the next usage of StringBuilder, which should be the desired field.
    val fieldUsageIndex = indexOfFirstInstruction(stringUsageIndex + 1) {
        val reference = getReference<MethodReference>()
        reference?.definingClass == "Ljava/lang/StringBuilder;" && reference.name == "append"
    }
    if (fieldUsageIndex < 0) {
        // Should never happen.
        throw IllegalArgumentException("Could not find StringBuilder append usage in: $this")
    }
    val fieldUsageInstruction = getInstruction(fieldUsageIndex)
    var fieldUsageRegister = fieldUsageInstruction.registersUsed.getOrElse(1) { fieldUsageInstruction.registersUsed[0] }

    // Look backwards up the method to find the instruction that sets the register.
    var fieldSetIndex = indexOfFirstInstructionReversedOrThrow(fieldUsageIndex - 1) {
        fieldUsageRegister == writeRegister
    }

    // Some 'toString()' methods, despite using a StringBuilder, Convert the value via
    // 'Object.toString()' or 'String.valueOf(object)' before appending it to the StringBuilder.
    // In this case, the correct index cannot be found.
    // Additional validation is done to find the index of the correct field or method.
    //
    // Check up to 3 method calls.
    var checksLeft = 3
    while (checksLeft > 0) {
        // If the field is a method call, then adjust from MOVE_RESULT to the method call.
        val fieldSetOpcode = getInstruction(fieldSetIndex).opcode
        if (fieldSetOpcode == MOVE_RESULT ||
            fieldSetOpcode == MOVE_RESULT_WIDE ||
            fieldSetOpcode == MOVE_RESULT_OBJECT
        ) {
            fieldSetIndex--
        }

        val fieldSetInstruction = getInstruction(fieldSetIndex)

        // If the instruction is a register MOVE (e.g. move-object/16, move/16),
        // trace backwards to find where the source register was set.
        if (fieldSetInstruction is TwoRegisterInstruction &&
            fieldSetInstruction.opcode in moveOpcodes
        ) {
            fieldUsageRegister = fieldSetInstruction.registerB
            fieldSetIndex = indexOfFirstInstructionReversedOrThrow(fieldSetIndex - 1) {
                fieldUsageRegister == writeRegister
            }
            continue
        }

        val fieldSetReference = fieldSetInstruction.getReference<Reference>()

        if (isField && fieldSetReference is FieldReference ||
            !isField && fieldSetReference is MethodReference
        ) {
            // Valid index.
            return fieldSetIndex
        } else if (fieldSetReference is MethodReference &&
            // Object.toString(), String.valueOf(object)
            fieldSetReference.returnType == "Ljava/lang/String;"
        ) {
            // FiveRegister or RegisterRange instruction.
            fieldUsageRegister = fieldSetInstruction.registersUsed[0]

            // Look backwards up the method to find the instruction that sets the register.
            fieldSetIndex = indexOfFirstInstructionReversedOrThrow(fieldSetIndex - 1) {
                fieldUsageRegister == writeRegister
            }
            checksLeft--
        } else {
            throw IllegalArgumentException("Unknown reference or instruction: $fieldSetInstruction")
        }
    }

    return fieldSetIndex
}

/**
 * Find the method used for a toString() StringBuilder write of a given String name.
 *
 * @param fieldName The name of the field to find. Partial matches are allowed.
 */
context(patchContext: BytecodePatchContext)
fun Method.findMethodFromToString(fieldName: String) : MutableMethod {
    val methodUsageIndex = findInstructionIndexFromToString(fieldName, false)
    return getInstruction(methodUsageIndex).getReference<MethodReference>()!!.getMutableMethod()
}

/**
 * Find the field used for a toString() StringBuilder write of a given String name.
 *
 * @param fieldName The name of the field to find. Partial matches are allowed.
 */
fun Method.findFieldFromToString(fieldName: String) : FieldReference {
    val methodUsageIndex = findInstructionIndexFromToString(fieldName, true)
    return getInstruction<ReferenceInstruction>(methodUsageIndex).getReference<FieldReference>()!!
}

/**
 * Adds public [AccessFlags] and removes private and protected flags (if present).
 */
fun Int.toPublicAccessFlags(): Int {
    return this.or(AccessFlags.PUBLIC.value)
        .and(AccessFlags.PROTECTED.value.inv())
        .and(AccessFlags.PRIVATE.value.inv())
}

/**
 * Find the [MutableMethod] from a given [Method] in a [MutableClass].
 *
 * @param method The [Method] to find.
 * @return The [MutableMethod].
 */
fun MutableClass.findMutableMethodOf(method: MethodReference) = this.methods.first {
    MethodUtil.methodSignaturesMatch(it, method)
}

/**
 * Apply a transform to all methods of the class.
 *
 * @param transform The transformation function. Accepts a [MutableMethod] and returns a transformed [MutableMethod].
 */
fun MutableClass.transformMethods(transform: MutableMethod.() -> MutableMethod) {
    val transformedMethods = methods.map { it.transform() }
    methods.clear()
    methods.addAll(transformedMethods)
}

/**
 * Inserts instructions at a given index, using the existing control flow label at that index.
 * Inserted instructions can have its own control flow labels as well.
 *
 * Effectively this changes the code from:
 * :label
 * (original code)
 *
 * Into:
 * :label
 * (patch code)
 * (original code)
 */
fun MutableMethod.addInstructionsAtControlFlowLabel(
    insertIndex: Int,
    instructions: String,
    vararg externalLabels: ExternalLabel
) {
    // Duplicate original instruction and add to +1 index.
    addInstruction(insertIndex + 1, getInstruction(insertIndex))

    // Add patch code at same index as duplicated instruction,
    // so it uses the original instruction control flow label.
    addInstructionsWithLabels(insertIndex + 1, instructions, *externalLabels)

    // Remove original non duplicated instruction.
    removeInstruction(insertIndex)

    // Original instruction is now after the inserted patch instructions,
    // and the original control flow label is on the first instruction of the patch code.
}

/**
 * Get the index of the first instruction with the id of the given resource id name.
 *
 * @param resourceName the name of the resource to find the id for.
 * @return the index of the first instruction with the id of the given resource name, or -1 if not found.
 * @throws PatchException if the resource cannot be found.
 * @see [indexOfFirstResourceIdOrThrow], [indexOfFirstLiteralInstructionReversed]
 */
fun Method.indexOfFirstResourceId(resourceName: String): Int {
    return indexOfFirstLiteralInstruction(resourceId(ResourceType.ID, resourceName))
}

/**
 * Get the index of the first instruction with the id of the given resource name or throw a [PatchException].
 *
 * @throws [PatchException] if the resource is not found, or the method does not contain the resource id literal value.
 * @see [indexOfFirstResourceId], [indexOfFirstLiteralInstructionReversedOrThrow]
 */
fun Method.indexOfFirstResourceIdOrThrow(resourceName: String): Int {
    val index = indexOfFirstResourceId(resourceName)
    if (index < 0) {
        throw PatchException("Found resource id for: '$resourceName' but method does not contain the id: $this")
    }

    return index
}

/**
 * Find the index of the first literal instruction with the given long value.
 *
 * @return the first literal instruction with the value, or -1 if not found.
 * @see indexOfFirstLiteralInstructionOrThrow
 */
fun Method.indexOfFirstLiteralInstruction(literal: Long) = implementation?.let {
    it.instructions.indexOfFirst { instruction ->
        (instruction as? WideLiteralInstruction)?.wideLiteral == literal
    }
} ?: -1

/**
 * Find the index of the first literal instruction with the given long value,
 * or throw an exception if not found.
 *
 * @return the first literal instruction with the value, or throws [PatchException] if not found.
 */
fun Method.indexOfFirstLiteralInstructionOrThrow(literal: Long): Int {
    val index = indexOfFirstLiteralInstruction(literal)
    if (index < 0) throw PatchException("Could not find long literal: $literal")
    return index
}

/**
 * Find the index of the first literal instruction with the given float value.
 *
 * @return the first literal instruction with the value, or -1 if not found.
 * @see indexOfFirstLiteralInstructionOrThrow
 */
fun Method.indexOfFirstLiteralInstruction(literal: Float) =
    indexOfFirstLiteralInstruction(literal.toRawBits().toLong())

/**
 * Find the index of the first literal instruction with the given float value,
 * or throw an exception if not found.
 *
 * @return the first literal instruction with the value, or throws [PatchException] if not found.
 */
fun Method.indexOfFirstLiteralInstructionOrThrow(literal: Float): Int {
    val index = indexOfFirstLiteralInstruction(literal)
    if (index < 0) throw PatchException("Could not find float literal: $literal")
    return index
}

/**
 * Find the index of the first literal instruction with the given double value.
 *
 * @return the first literal instruction with the value, or -1 if not found.
 * @see indexOfFirstLiteralInstructionOrThrow
 */
fun Method.indexOfFirstLiteralInstruction(literal: Double) =
    indexOfFirstLiteralInstruction(literal.toRawBits())

/**
 * Find the index of the first literal instruction with the given double value,
 * or throw an exception if not found.
 *
 * @return the first literal instruction with the value, or throws [PatchException] if not found.
 */
fun Method.indexOfFirstLiteralInstructionOrThrow(literal: Double): Int {
    val index = indexOfFirstLiteralInstruction(literal)
    if (index < 0) throw PatchException("Could not find double literal: $literal")
    return index
}

/**
 * Find the index of the last literal instruction with the given value.
 *
 * @return the last literal instruction with the value, or -1 if not found.
 * @see indexOfFirstLiteralInstructionOrThrow
 */
fun Method.indexOfFirstLiteralInstructionReversed(literal: Long) = implementation?.let {
    it.instructions.indexOfLast { instruction ->
        (instruction as? WideLiteralInstruction)?.wideLiteral == literal
    }
} ?: -1

/**
 * Find the index of the last wide literal instruction with the given long value,
 * or throw an exception if not found.
 *
 * @return the last literal instruction with the value, or throws [PatchException] if not found.
 */
fun Method.indexOfFirstLiteralInstructionReversedOrThrow(literal: Long): Int {
    val index = indexOfFirstLiteralInstructionReversed(literal)
    if (index < 0) throw PatchException("Could not find long literal: $literal")
    return index
}

/**
 * Find the index of the last literal instruction with the given float value.
 *
 * @return the last literal instruction with the value, or -1 if not found.
 * @see indexOfFirstLiteralInstructionOrThrow
 */
fun Method.indexOfFirstLiteralInstructionReversed(literal: Float) =
    indexOfFirstLiteralInstructionReversed(literal.toRawBits().toLong())

/**
 * Find the index of the last wide literal instruction with the given float value,
 * or throw an exception if not found.
 *
 * @return the last literal instruction with the value, or throws [PatchException] if not found.
 */
fun Method.indexOfFirstLiteralInstructionReversedOrThrow(literal: Float): Int {
    val index = indexOfFirstLiteralInstructionReversed(literal)
    if (index < 0) throw PatchException("Could not find float literal: $literal")
    return index
}

/**
 * Find the index of the last literal instruction with the given double value.
 *
 * @return the last literal instruction with the value, or -1 if not found.
 * @see indexOfFirstLiteralInstructionOrThrow
 */
fun Method.indexOfFirstLiteralInstructionReversed(literal: Double) =
    indexOfFirstLiteralInstructionReversed(literal.toRawBits())

/**
 * Find the index of the last wide literal instruction with the given double value,
 * or throw an exception if not found.
 *
 * @return the last literal instruction with the value, or throws [PatchException] if not found.
 */
fun Method.indexOfFirstLiteralInstructionReversedOrThrow(literal: Double): Int {
    val index = indexOfFirstLiteralInstructionReversed(literal)
    if (index < 0) throw PatchException("Could not find double literal: $literal")
    return index
}

/**
 * Check if the method contains a literal with the given long value.
 *
 * @return if the method contains a literal with the given value.
 */
fun Method.containsLiteralInstruction(literal: Long) = indexOfFirstLiteralInstruction(literal) >= 0

/**
 * Check if the method contains a literal with the given float value.
 *
 * @return if the method contains a literal with the given value.
 */
fun Method.containsLiteralInstruction(literal: Float) = indexOfFirstLiteralInstruction(literal) >= 0

/**
 * Check if the method contains a literal with the given double value.
 *
 * @return if the method contains a literal with the given value.
 */
fun Method.containsLiteralInstruction(literal: Double) = indexOfFirstLiteralInstruction(literal) >= 0

/**
 * Traverse the class hierarchy starting from the given root class.
 *
 * @param targetClass the class to start traversing the class hierarchy from.
 * @param callback function that is called for every class in the hierarchy.
 */
fun BytecodePatchContext.traverseClassHierarchy(targetClass: MutableClass, callback: MutableClass.() -> Unit) {
    callback(targetClass)

    targetClass.superclass ?: return

    mutableClassDefByOrNull(targetClass.superclass!!)?.let {
        traverseClassHierarchy(it, callback)
    }
}

/**
 * @return The mutable method for this method call reference.
 */
context(patchContext: BytecodePatchContext)
fun MethodReference.getMutableMethod(): MutableMethod {
    return patchContext.mutableClassDefBy(this.definingClass).methods.first { classMethod ->
        MethodUtil.methodSignaturesMatch(classMethod, this@getMutableMethod)
    }
}

/**
 * @return The index of the first opcode specified, or -1 if not found.
 * @see indexOfFirstInstructionOrThrow
 */
fun Method.indexOfFirstInstruction(targetOpcode: Opcode): Int = indexOfFirstInstruction(0, targetOpcode)

/**
 * @param startIndex Optional starting index to start searching from.
 * @return The index of the first opcode specified, or -1 if not found.
 * @see indexOfFirstInstructionOrThrow
 */
fun Method.indexOfFirstInstruction(startIndex: Int = 0, targetOpcode: Opcode): Int =
    indexOfFirstInstruction(startIndex) {
        opcode == targetOpcode
    }

/**
 * Get the index of the first [Instruction] that matches the predicate, starting from [startIndex].
 *
 * @param startIndex Optional starting index to start searching from.
 * @return -1 if the instruction is not found.
 * @see indexOfFirstInstructionOrThrow
 */
fun Method.indexOfFirstInstruction(startIndex: Int = 0, filter: Instruction.() -> Boolean): Int {
    var instructions = this.implementation?.instructions ?: return -1
    if (startIndex != 0) {
        instructions = instructions.drop(startIndex)
    }
    val index = instructions.indexOfFirst(filter)

    return if (index >= 0) {
        startIndex + index
    } else {
        -1
    }
}

/**
 * Get the index of matching instruction.
 *
 * @return -1 if the instruction is not found.
 * @see indexOfFirstInstructionOrThrow
 */
fun Method.indexOfFirstInstruction(filter: InstructionFilter): Int = indexOfFirstInstruction(0, filter)

/**
 * Get the index of matching instruction, starting from [startIndex].
 *
 * @param startIndex Optional starting index to search from.
 * @return -1 if the instruction is not found.
 * @see indexOfFirstInstructionOrThrow
 */
fun Method.indexOfFirstInstruction(startIndex: Int = 0, filter: InstructionFilter): Int {
    val method = this
    return indexOfFirstInstruction(startIndex) { filter.matches(method, this) }
}

/**
 * @return The index of the first opcode specified
 * @throws PatchException
 * @see indexOfFirstInstruction
 */
fun Method.indexOfFirstInstructionOrThrow(targetOpcode: Opcode): Int = indexOfFirstInstructionOrThrow(0, targetOpcode)

/**
 * @return The index of the first opcode specified, starting from the index specified.
 * @throws PatchException
 * @see indexOfFirstInstruction
 */
fun Method.indexOfFirstInstructionOrThrow(startIndex: Int = 0, targetOpcode: Opcode): Int =
    indexOfFirstInstructionOrThrow(startIndex) {
        opcode == targetOpcode
    }

/**
 * Get the index of the first [Instruction] that matches the predicate, starting from [startIndex].
 *
 * @return The index of the instruction.
 * @throws PatchException
 * @see indexOfFirstInstruction
 */
fun Method.indexOfFirstInstructionOrThrow(startIndex: Int = 0, filter: Instruction.() -> Boolean): Int {
    val index = indexOfFirstInstruction(startIndex, filter)
    if (index < 0) {
        throw PatchException("Could not find instruction index")
    }

    return index
}

/**
 * Get the index of matching instruction.
 *
 * @return The index of the instruction.
 * @throws PatchException
 * @see indexOfFirstInstruction
 */
fun Method.indexOfFirstInstructionOrThrow(filter: InstructionFilter): Int = indexOfFirstInstructionOrThrow(0, filter)

/**
 * Get the index of matching instruction, starting from [startIndex].
 *
 * @return The index of the instruction.
 * @throws PatchException
 * @see indexOfFirstInstruction
 */
fun Method.indexOfFirstInstructionOrThrow(startIndex: Int = 0, filter: InstructionFilter): Int {
    val method = this
    return indexOfFirstInstructionOrThrow(startIndex) { filter.matches(method, this) }
}

fun Method.indexOfFirstStringInstruction(str: String) =
    indexOfFirstInstruction {
        getReference<StringReference>()?.string == str
    }

fun Method.indexOfFirstStringInstructionOrThrow(str: String): Int {
    val index = indexOfFirstStringInstruction(str)
    if (index < 0) {
        throw PatchException("Could not find string instruction: '$str' in $this")
    }

    return index
}

/**
 * Get the index of matching instruction,
 * starting from and [startIndex] and searching down.
 *
 * @param startIndex Optional starting index to search down from. Searching includes the start index.
 * @return -1 if the instruction is not found.
 * @see indexOfFirstInstructionReversedOrThrow
 */
fun Method.indexOfFirstInstructionReversed(startIndex: Int? = null, targetOpcode: Opcode): Int =
    indexOfFirstInstructionReversed(startIndex) {
        opcode == targetOpcode
    }

/**
 * Get the index of matching instruction,
 * starting from and [startIndex] and searching down.
 *
 * @param startIndex Optional starting index to search down from. Searching includes the start index.
 * @return -1 if the instruction is not found.
 * @see indexOfFirstInstructionReversedOrThrow
 */
fun Method.indexOfFirstInstructionReversed(startIndex: Int? = null, filter: Instruction.() -> Boolean): Int {
    var instructions = this.implementation?.instructions ?: return -1
    if (startIndex != null) {
        instructions = instructions.take(startIndex + 1)
    }

    return instructions.indexOfLast(filter)
}

/**
 * Get the index of matching instruction,
 * starting from the end of the method and searching down.
 *
 * @return -1 if the instruction is not found.
 * @see indexOfFirstInstructionReversedOrThrow
 */
fun Method.indexOfFirstInstructionReversed(filter: InstructionFilter): Int = indexOfFirstInstructionReversed(null, filter)

/**
 * Get the index of matching instruction,
 * starting from and [startIndex] and searching down.
 *
 * @param startIndex Optional starting index to search down from. Searching includes the start index.
 * @return -1 if the instruction is not found.
 * @see indexOfFirstInstructionReversedOrThrow
 */
fun Method.indexOfFirstInstructionReversed(startIndex: Int? = null, filter: InstructionFilter): Int {
    val method = this
    return indexOfFirstInstructionReversed(startIndex) { filter.matches(method, this) }
}

/**
 * Get the index of matching instruction,
 * starting from the end of the method and searching down.
 *
 * @return -1 if the instruction is not found.
 */
fun Method.indexOfFirstInstructionReversed(targetOpcode: Opcode): Int = indexOfFirstInstructionReversed {
    opcode == targetOpcode
}

/**
 * Get the index of matching instruction,
 * starting from [startIndex] and searching down.
 *
 * @param startIndex Optional starting index to search down from. Searching includes the start index.
 * @return The index of the instruction.
 * @see indexOfFirstInstructionReversed
 */
fun Method.indexOfFirstInstructionReversedOrThrow(startIndex: Int? = null, targetOpcode: Opcode): Int =
    indexOfFirstInstructionReversedOrThrow(startIndex) {
        opcode == targetOpcode
    }

/**
 * Get the index of matching instruction,
 * starting from the end of the method and searching down.
 *
 * @return -1 if the instruction is not found.
 */
fun Method.indexOfFirstInstructionReversedOrThrow(targetOpcode: Opcode): Int = indexOfFirstInstructionReversedOrThrow {
    opcode == targetOpcode
}

/**
 * Get the index of matching instruction,
 * starting from [startIndex] and searching down.
 *
 * @param startIndex Optional starting index to search down from. Searching includes the start index.
 * @return The index of the instruction.
 * @see indexOfFirstInstructionReversed
 */
fun Method.indexOfFirstInstructionReversedOrThrow(startIndex: Int? = null, filter: Instruction.() -> Boolean): Int {
    val index = indexOfFirstInstructionReversed(startIndex, filter)

    if (index < 0) {
        throw PatchException("Could not find instruction index")
    }

    return index
}

/**
 * Get the index of matching instruction,
 * starting from the end of the method and searching down.
 *
 * @return The index of the instruction.
 * @throws PatchException
 * @see indexOfFirstInstructionReversed
 */
fun Method.indexOfFirstInstructionReversedOrThrow(filter: InstructionFilter): Int = indexOfFirstInstructionReversedOrThrow(null, filter)

/**
 * Get the index of matching instruction,
 * starting from [startIndex] and searching down.
 *
 * @param startIndex Optional starting index to search down from. Searching includes the start index.
 * @return The index of the instruction.
 * @see indexOfFirstInstructionReversed
 */
fun Method.indexOfFirstInstructionReversedOrThrow(startIndex: Int? = null, filter: InstructionFilter): Int {
    val method = this
    return indexOfFirstInstructionReversedOrThrow(startIndex) {
        filter.matches(method, this)
    }
}

/**
 * @return A list of indices of the instructions in reverse order.
 *  _Returns an empty list if no indices are found_
 *  @see findInstructionIndicesReversedOrThrow
 */
fun Method.findInstructionIndicesReversed(filter: Instruction.() -> Boolean): List<Int> = instructions
    .withIndex()
    .filter { (_, instruction) -> filter(instruction) }
    .map { (index, _) -> index }
    .asReversed()

/**
 * @return A list of indices of the instructions in reverse order.
 * @throws PatchException if no matching indices are found.
 */
fun Method.findInstructionIndicesReversedOrThrow(filter: Instruction.() -> Boolean): List<Int> {
    val indexes = findInstructionIndicesReversed(filter)
    if (indexes.isEmpty()) throw PatchException("No matching instructions found in: $this")

    return indexes
}

/**
 * @return A list of indices of the opcode in reverse order.
 *  _Returns an empty list if no indices are found_
 * @see findInstructionIndicesReversedOrThrow
 */
fun Method.findInstructionIndicesReversed(opcode: Opcode): List<Int> =
    findInstructionIndicesReversed { this.opcode == opcode }

/**
 * @return A list of indices of the opcode in reverse order.
 * @throws PatchException if no matching indices are found.
 */
fun Method.findInstructionIndicesReversedOrThrow(opcode: Opcode): List<Int> {
    val instructions = findInstructionIndicesReversed(opcode)
    if (instructions.isEmpty()) throw PatchException("Could not find opcode: $opcode in: $this")

    return instructions
}

/**
 * @return A list of indices of the instructions in reverse order.
 * _Returns an empty list if no indices are found_
 * @throws PatchException if no matching indices are found.
 */
fun Method.findInstructionIndicesReversed(filter: InstructionFilter): List<Int> {
    val method = this
    return findInstructionIndicesReversed {
        filter.matches(method, this)
    }
}

/**
 * @return A list of indices of the instructions in reverse order.
 * @throws PatchException if no matching indices are found.
 */
fun Method.findInstructionIndicesReversedOrThrow(filter: InstructionFilter): List<Int> {
    val indexes = findInstructionIndicesReversed(filter)
    if (indexes.isEmpty()) throw PatchException("No matching instructions found in: $this")

    return indexes
}

/**
 * Overrides the first move result with an extension call.
 */
fun MutableMethod.insertLiteralOverride(literal: Long, extensionMethodDescriptor: String) {
    val literalIndex = indexOfFirstLiteralInstructionOrThrow(literal)
    insertLiteralOverride(literalIndex, extensionMethodDescriptor)
}


/**
 * Maps each instruction index to its starting code unit offset, so that branch targets
 * (given as code unit offsets) can be resolved back to instruction indices.
 */
fun Method.instructionCodeOffsets(): IntArray {
    val instructionList = this.implementation?.instructions?.toList() ?: return IntArray(0)
    val offsets = IntArray(instructionList.size)
    var offset = 0
    instructionList.forEachIndexed { index, instruction ->
        offsets[index] = offset
        offset += instruction.codeUnits
    }
    return offsets
}

/**
 * Resolves the instruction index a branch instruction at [branchIndex] jumps to.
 */
private fun Method.branchTargetIndex(branchIndex: Int, codeOffsets: IntArray): Int {
    val branchInstruction = getInstruction<Instruction>(branchIndex) as? OffsetInstruction
        ?: throw IllegalArgumentException(
            "Instruction at index: $branchIndex in method: $this is not a branch instruction."
        )
    val targetOffset = codeOffsets[branchIndex] + branchInstruction.codeOffset
    val targetIndex = codeOffsets.indexOfFirst { it == targetOffset }
    require(targetIndex >= 0) {
        "Could not resolve branch target offset: $targetOffset from index: $branchIndex in method: $this"
    }
    return targetIndex
}

/**
 * Walks the control flow graph forward from [startIndex], following both conditional
 * and unconditional branches to their real targets, searching for all instructions
 * that use [register] as a method call argument, until the register is clobbered.
 * A wide literal also uses the register after [register], so a write to either register,
 * including a wide write to the register before it, clobbers the literal.
 *
 * A breadth first search is used so paths are explored in order of how many instructions
 * actually execute before reaching them.
 *
 * @return A list of instruction indices that use the register as a method call argument.
 */
private fun Method.findAllReachableLiteralEvents(
    startIndex: Int,
    register: Int,
    isWideLiteral: Boolean,
): List<Int> {
    val instructionList = this.implementation?.instructions?.toList() ?: return emptyList()
    val codeOffsets = instructionCodeOffsets()
    val visited = HashSet<Int>()
    val usageIndices = mutableListOf<Int>()
    val queue = ArrayDeque<Int>()
    queue.add(startIndex)

    while (queue.isNotEmpty()) {
        val index = queue.removeFirst()
        if (index < 0 || index >= instructionList.size || !visited.add(index)) {
            continue
        }
        val instruction = instructionList[index]

        val writeRegister = instruction.writeRegister
        if (writeRegister != null) {
            val writeRegisterEnd = if (instruction.opcode.setsWideRegister()) writeRegister + 1 else writeRegister
            val literalRegisterEnd = if (isWideLiteral) register + 1 else register
            if (writeRegister <= literalRegisterEnd && register <= writeRegisterEnd) {
                continue
            }
        }

        val methodReference = instruction.getReference<MethodReference>()
        if (methodReference != null &&
            (instruction as? FiveRegisterInstruction)?.registersUsed?.contains(register) == true
        ) {
            usageIndices.add(index)
        }

        when {
            instruction.isUnconditionalBranchInstruction -> {
                queue.add(branchTargetIndex(index, codeOffsets))
            }
            instruction.isConditionalBranchInstruction -> {
                queue.add(index + 1)
                queue.add(branchTargetIndex(index, codeOffsets))
            }
            instruction.isSwitchInstruction -> {
                queue.add(index + 1)
                val payloadIndex = branchTargetIndex(index, codeOffsets)
                val payload = instructionList[payloadIndex] as? SwitchPayload
                payload?.switchElements?.forEach { element ->
                    val targetOffset = codeOffsets[index] + element.offset
                    val targetIndex = codeOffsets.indexOfFirst { it == targetOffset }
                    if (targetIndex >= 0) queue.add(targetIndex)
                }
            }
            instruction.isReturnInstruction -> {
                // Dead end: nothing further executes along this path.
            } else -> {
                queue.add(index + 1)
            }
        }
    }

    return usageIndices
}

/**
 * Overrides *all* usage of the literal declared at the provided index. This override
 * includes if the literal was used multiple times in the same method.
 */
fun MutableMethod.insertLiteralOverride(literalIndexStart: Int, extensionMethodDescriptor: String) {
    val useLogging = false

    val startInstruction = getInstruction<OneRegisterInstruction>(literalIndexStart)
    require(startInstruction is WideLiteralInstruction) {
        "literal index: $literalIndexStart in method: $this is not a literal instruction: " +
                "${startInstruction.opcode} $startInstruction"
    }
    val literalValue = startInstruction.wideLiteral
    val literalFilter = literal(startInstruction.wideLiteral)

    // Some literals are used multiple times in the same method. Insertions can shift
    // indices in either direction (forward usage vs. a backward loop-carried usage),
    // so occurrences are re-scanned each iteration.
    var processedCount = 0
    while (true) {
        val currentMatches = instructions
            .withIndex()
            .filter { (_, instruction) -> literalFilter.matches(this, instruction) }
        if (processedCount >= currentMatches.size) return

        val literalIndex = currentMatches[processedCount++].index
        val literalInstruction = getInstruction<OneRegisterInstruction>(literalIndex)
        val literalRegister = literalInstruction.registerA

        val usageIndices = findAllReachableLiteralEvents(
            literalIndex + 1,
            literalRegister,
            literalInstruction.opcode.setsWideRegister()
        )
        if (usageIndices.isEmpty()) {
            if (useLogging) {
                println("""
                        Ignoring literal with no reachable usage (clobbered or dead)
                        literalValue: $literalValue
                        literalIndex: $literalIndex
                        method: $this
                    """
                )
            }
            continue
        }

        val moveResultOpcode = if (extensionMethodDescriptor.endsWith(";")) {
            MOVE_RESULT_OBJECT
        } else if (extensionMethodDescriptor.endsWith("J") ||
            extensionMethodDescriptor.endsWith("D")
        ) {
            MOVE_RESULT_WIDE
        } else {
            MOVE_RESULT
        }

        usageIndices.sortedDescending().forEach { usageIndex ->
            val moveResultIndex = usageIndex + 1
            val moveResultInstruction = getInstruction(moveResultIndex)
            if (moveResultInstruction.opcode != moveResultOpcode) {
                // Method return value is not used.
                if (useLogging) println(
                    """
                        Ignoring literal with ignored return value
                        literalValue: $literalValue
                        literalIndex: $literalIndex
                        literalMethodCall: ${getInstruction<ReferenceInstruction>(usageIndex).reference}
                        method: $this"
                    """
                )
                return@forEach
            }

            val isWide = moveResultOpcode == MOVE_RESULT_WIDE
            val register = (moveResultInstruction as OneRegisterInstruction).registerA
            val endRegister = if (isWide) register + 1 else register
            val operation = if (endRegister < 16) {
                if (isWide) {
                    "invoke-static { v$register, v$endRegister }"
                } else {
                    "invoke-static { v$register }"
                }
            } else {
                "invoke-static/range { v$register .. v$endRegister }"
            }
            val moveResultSmali = when (moveResultOpcode) {
                MOVE_RESULT_OBJECT -> "move-result-object"
                MOVE_RESULT_WIDE -> "move-result-wide"
                else -> "move-result"
            }

            addInstructions(
                moveResultIndex + 1,
                """
                    $operation, $extensionMethodDescriptor
                    $moveResultSmali v$register
                """
            )
        }
    }
}

/**
 * Overrides a literal value result with a constant value.
 */
fun MutableMethod.insertLiteralOverride(literal: Long, override: Boolean) {
    val literalIndex = indexOfFirstLiteralInstructionOrThrow(literal)
    return insertLiteralOverride(literalIndex, override)
}

/**
 * Constant value override of all MOVE_RESULT instructions that use the literal declared at the provided index.
 */
fun MutableMethod.insertLiteralOverride(literalIndexStart: Int, override: Boolean) {
    val startInstruction = getInstruction<OneRegisterInstruction>(literalIndexStart)
    val literalRegister = startInstruction.registerA
    val overrideValue = if (override) "0x1" else "0x0"

    val usageIndices = findAllReachableLiteralEvents(
        literalIndexStart + 1,
        literalRegister,
        startInstruction.opcode.setsWideRegister()
    )
    if (usageIndices.isEmpty()) return

    usageIndices.sortedDescending().forEach { usageIndex ->
        val index = usageIndex + 1
        if (index >= instructions.count()) return@forEach

        val instruction = getInstruction(index)
        if (instruction is OneRegisterInstruction && instruction.opcode == MOVE_RESULT) {
            val register = instruction.registerA
            addInstruction(
                index + 1,
                "const v$register, $overrideValue"
            )
        }
    }
}

/**
 * Adapted from BiliRoamingX:
 * https://github.com/BiliRoamingX/BiliRoamingX/blob/ae58109f3acdd53ec2d2b3fb439c2a2ef1886221/patches/src/main/kotlin/app/revanced/patches/bilibili/utils/Extenstions.kt#L51
 *
 * Additional registers effectively take the place of the pX parameters (p0, p1, p2, etc.)
 * and contain the original contents of the method parameters.
 * Added registers always start at index: `originalMethod.implementation!!.registerCount` of the
 * original uncloned method.
 *
 * **Fingerprint match indexes will be increased positively by
 * [numberOfParameterRegistersLogical] of the cloned method**.
 */
fun Method.cloneMutable(
    name: String = this.name,
    accessFlags: Int = this.accessFlags,
    parameters: List<MethodParameter> = this.parameters,
    returnType: String = this.returnType,
    additionalRegisters: Int = 0,
): MutableMethod {
    check(additionalRegisters >= 0) {
        "Additional registers cannot be negative"
    }

    val implementationExists = implementation != null
    val oldFirstParameterRegister = if (implementationExists) p0Register else 0

    val clonedImplementation = implementation?.let {
        ImmutableMethodImplementation(
            it.registerCount + additionalRegisters,
            it.instructions,
            it.tryBlocks,
            it.debugItems,
        )
    }

    return ImmutableMethod(
        definingClass,
        name,
        parameters,
        returnType,
        accessFlags,
        annotations,
        hiddenApiRestrictions,
        clonedImplementation
    ).toMutable().apply {
        var insertIndex = 0
        var addedInstructions = 0
        val isNotStatic = !AccessFlags.STATIC.isSet(accessFlags)

        if (implementationExists && additionalRegisters > 0 && (parameters.isNotEmpty() || isNotStatic)) {
            var destReg = oldFirstParameterRegister
            var pReg = 0

            // Handle `this`.
            if (isNotStatic) {
                addInstructions(insertIndex++, "move-object/from16 v$destReg, p0")
                addedInstructions++
                destReg += 1
                pReg += 1
            }

            // Handle method parameters.
            for (parameter in parameters) {
                val opcode = when (parameter.type) {
                    "J", "D" -> "move-wide/from16"
                    else -> {
                        if (parameter.type.startsWith('L') || parameter.type.startsWith('[')) {
                            "move-object/from16"
                        } else {
                            "move/from16"
                        }
                    }
                }

                addInstructions(insertIndex++, "$opcode v$destReg, p$pReg")
                addedInstructions++

                val width = if (opcode.startsWith("move-wide")) 2 else 1
                destReg += width
                pReg += width
            }

            if (addedInstructions != numberOfParameterRegistersLogical) {
                throw IllegalStateException(
                    "Added instructions do not match additional registers " +
                            "addedInstructions: $addedInstructions " +
                            "numberOfParameterRegistersLogical: $numberOfParameterRegistersLogical"
                )
            }
        }
    }
}

fun Boolean.toHexString(): String = if (this) "0x1" else "0x0"

/**
 * Adapted from BiliRoamingX:
 * https://github.com/BiliRoamingX/BiliRoamingX/blob/ae58109f3acdd53ec2d2b3fb439c2a2ef1886221/patches/src/main/kotlin/app/revanced/patches/bilibili/utils/Extenstions.kt#L151
 */
fun MutableMethod.fiveRegisters(index: Int) = getInstruction<FiveRegisterInstruction>(index)
    .registersUsed.joinToString(",") { "v$it" }

/**
 * Remove the given AccessFlags from the field.
 */
fun MutableField.removeFlags(vararg flags: AccessFlags) {
    val bitField = flags.map { it.value }.reduce { acc, flag -> acc or flag }
    this.accessFlags = this.accessFlags and bitField.inv()
}

fun BytecodePatchContext.addStaticFieldToExtension(
    className: String,
    methodName: String,
    fieldName: String,
    objectClass: String,
    smaliInstructions: String
) {
    val mutableClass = mutableClassDefBy(className)
    val objectCall = "$mutableClass->$fieldName:$objectClass"

    mutableClass.apply {
        methods.first { method -> method.name == methodName }.apply {
            staticFields.add(
                ImmutableField(
                    definingClass,
                    fieldName,
                    objectClass,
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
                    null,
                    annotations,
                    null
                ).toMutable()
            )

            addInstructionsWithLabels(
                0,
                """
                    sget-object v0, $objectCall
                """ + smaliInstructions
            )
        }
    }
}

context(patchContext: BytecodePatchContext)
fun setExtensionIsPatchIncluded(patchExtensionClassType: String) {
    val methodName = "isPatchIncluded"
    val returnType = "Z"

    val fingerprint = Fingerprint(
        definingClass = patchExtensionClassType,
        name = methodName,
        returnType = returnType,
        parameters = listOf(),
        custom = { method, _ ->
            AccessFlags.STATIC.isSet(method.accessFlags)
        }
    )

    if (fingerprint.methodOrNull == null) {
        throw PatchException(
            "Could not find required extension method: $patchExtensionClassType->$methodName()$returnType"
        )
    }

    fingerprint.method.returnEarly(true)
}

/**
 * Get the first constructor.
 */
fun MutableClass.constructor() =
    this.methods.first { AccessFlags.CONSTRUCTOR.isSet(it.accessFlags) }


/**
 * Get the first field with the given name.
 */
fun MutableClass.fieldByName(name: String): MutableField {
    return this.fields.first { it.name == name }
}

/**
 * Get the public toString() method.
 */
fun ClassDef.toStringMethod(): Method? =
    this.methods.first {
        it.name == "toString" && AccessFlags.PUBLIC.isSet(it.accessFlags) && it.parameters.isEmpty()
    }

/**
 * Add instructions `indexFromEnd` places before the end of the method.
 */
fun MutableMethod.addInstructionsToEnd(indexFromEnd: Int, smaliInstructions: String) =
    this.addInstructions(this.instructions.count() - indexFromEnd, smaliInstructions)

/**
 * Add instructions to end of method before final return instruction.
 */
fun MutableMethod.addInstructionsToEnd(smaliInstructions: String) =
    this.addInstructionsToEnd(1, smaliInstructions)

/**
 * This differs from [isUnconditionalBranchInstruction] in that it does not include unconditional goto.
 *
 * @return If this instruction is a conditional branch (multiple branch paths).
 */
internal val Instruction.isConditionalBranchInstruction: Boolean
    get() = this.opcode in conditionalBranchOpcodes

/**
 * @return If this instruction is a GOTO opcode.
 */
internal val Instruction.isUnconditionalBranchInstruction: Boolean
    get() = this.opcode in unconditionalBranchOpcodes

/**
 * @return If this instruction is a switch opcode.
 */
internal val Instruction.isSwitchInstruction: Boolean
    get() = this.opcode in switchOpcodes

/**
 * @return If this instruction returns or throws.
 */
internal val Instruction.isReturnInstruction: Boolean
    get() = this.opcode in returnOpcodes

private val conditionalBranchOpcodes: EnumSet<Opcode> = EnumSet.of(
    IF_EQ, IF_NE, IF_LT, IF_GE, IF_GT, IF_LE,
    IF_EQZ, IF_NEZ, IF_LTZ, IF_GEZ, IF_GTZ, IF_LEZ
)

private val unconditionalBranchOpcodes: EnumSet<Opcode> = EnumSet.of(
    GOTO, GOTO_16, GOTO_32
)

private val switchOpcodes: EnumSet<Opcode> = EnumSet.of(
    PACKED_SWITCH, SPARSE_SWITCH
)

private val returnOpcodes: EnumSet<Opcode> = EnumSet.of(
    RETURN_VOID, RETURN, RETURN_WIDE, RETURN_OBJECT, RETURN_VOID_NO_BARRIER,
    THROW
)

private val moveOpcodes: EnumSet<Opcode> = EnumSet.of(
    Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16,
    Opcode.MOVE_WIDE, Opcode.MOVE_WIDE_FROM16, Opcode.MOVE_WIDE_16,
    Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16
)

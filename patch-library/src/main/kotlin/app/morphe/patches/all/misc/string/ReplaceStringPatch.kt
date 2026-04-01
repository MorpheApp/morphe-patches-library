package app.morphe.patches.all.misc.string

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference

@Suppress("unused")
fun replaceStringPatch(
    from: String,
    to: String,
    comparison: StringComparisonType = StringComparisonType.EQUALS,
) = bytecodePatch(
    description = "Replaces occurrences of '$from' with '$to' in string references.",
    default = false,
) {
    execute {
        val stringFilter = string(from, comparison)

        Fingerprint(
            filters = listOf(stringFilter)
        ).matchAllOrNull()?.forEach { match ->
            match.method.apply {
                findInstructionIndicesReversedOrThrow(stringFilter).forEach { index ->
                    val replacement = when (comparison) {
                        StringComparisonType.EQUALS -> to
                        else -> {
                            getInstruction<ReferenceInstruction>(index)
                                .getReference<StringReference>()!!.string.replace(from, to)
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
    }
}

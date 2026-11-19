/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.audioicon

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.classesReadingType
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val PATCH = "Hide the audio icon on posts"

internal const val TAG_INDICATOR = "$FEED_AUDIO_ICON->tagIndicator(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"

/**
 * A name only the enum of a media's corner indicator kinds passes its constructor in Instagram 456:
 * the kinds are NONE, PEOPLE, FB_USER, PRODUCTS and twenty more, OPEN_CAROUSEL_THANK among them (#762).
 */
internal const val INDICATOR_KIND_NAME = "OPEN_CAROUSEL_THANK"

/** The two kinds the enum's class initializer has to name too, the person icon's own. */
internal val PEOPLE_KIND_NAMES = listOf("PEOPLE", "FB_USER")

private const val ENUM = "Ljava/lang/Enum;"
private const val CLICK_LISTENER = "Landroid/view/View\$OnClickListener;"

/** How far into the bind the state has to be tested for null, as Instagram 456's is at instruction 4. */
private const val NULL_TEST_REACH = 12

/**
 * The bind of a post's corner tag indicator, found. Its [stateParameter] is the indicator's state, a
 * data class whose [kindField] holds the kind; with no state the bind takes its own path that hides
 * the indicator. [write] hands the state and its kind to FeedAudioIcon.tagIndicator() as the bind
 * starts and puts the answer back in the parameter's register, so a null answer takes that path.
 */
internal class TagIndicatorBind(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val stateParameter: Int,
    val stateType: String,
    val kindField: String,
    val kindType: String,
) {
    fun write(context: BytecodePatchContext) {
        val bind = context.mutableClassDefBy(type).methods.single { method ->
            method.name == name && method.returnType == "V" && method.parameterTypes.map(Any::toString) == parameters
        }
        if (bind.localRegisterCount() < 2) refuse("$type->$name has ${bind.localRegisterCount()} locals, the hook needs 2")
        val state = bind.parameterRegisterNumber(stateParameter)
        bind.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, v$state
                if-eqz v0, :keep
                iget-object v1, v0, $stateType->$kindField:$kindType
                invoke-static { v0, v1 }, $TAG_INDICATOR
                move-result-object v0
                check-cast v0, $stateType
                move-object/from16 v$state, v0
            """,
            ExternalLabel("keep", bind.getInstruction(0)),
        )
    }
}

/**
 * Finds the indicator kinds enum by the names its class initializer passes, then, among the classes
 * reading a field of that type, the one void method taking a click listener and a state it tests for
 * null near its start and reads its kind from. The view holder it's also handed keeps the last kind
 * in a field of its own, but it's never tested for null, which is how the state parameter is told
 * apart. Anything else is an update this patch hasn't seen, and it stops.
 */
internal fun BytecodePatchContext.findTagIndicatorBind(): TagIndicatorBind {
    val kind = tagIndicatorKinds()
    val binds = classesReadingType(kind.type).flatMap { classDef -> bindsIn(classDef, kind.type) }
    return binds.singleOrNull()
        ?: refuse("expected one bind of the indicator reading its ${kind.type} from a null-tested state, found ${binds.size}" +
            binds.joinToString(prefix = if (binds.isEmpty()) "" else ": ") { "${it.type}->${it.name}" })
}

/** The enum of indicator kinds: the one Enum loading [INDICATOR_KIND_NAME] whose initializer names both people kinds. */
internal fun BytecodePatchContext.tagIndicatorKinds(): ClassDef {
    val kinds = classesLoadingString(INDICATOR_KIND_NAME).filter { classDef ->
        classDef.superclass == ENUM && classDef.methods.any { method ->
            method.name == "<clinit>" && PEOPLE_KIND_NAMES.all { loadsString(method, it) }
        }
    }
    return kinds.singleOrNull()
        ?: refuse("expected one enum naming $INDICATOR_KIND_NAME and ${PEOPLE_KIND_NAMES.joinToString(" and ")}, found ${kinds.size}")
}

private fun bindsIn(classDef: ClassDef, kindType: String): List<TagIndicatorBind> =
    classDef.methods.filter { it.returnType == "V" && it.parameterTypes.any { type -> type.toString() == CLICK_LISTENER } }
        .flatMap { method ->
            val code = method.implementation?.instructions?.toList() ?: return@flatMap emptyList()
            val parameters = method.parameterTypes.map(Any::toString)
            parameters.indices.mapNotNull { index ->
                val stateType = parameters[index]
                if (!stateType.startsWith("L") || stateType == kindType) return@mapNotNull null
                val register = method.parameterRegisterNumber(index)
                val tested = code.take(NULL_TEST_REACH).any { instruction ->
                    (instruction.opcode == Opcode.IF_EQZ || instruction.opcode == Opcode.IF_NEZ) &&
                        (instruction as OneRegisterInstruction).registerA == register
                }
                if (!tested) return@mapNotNull null
                val field = code.firstNotNullOfOrNull { instruction -> kindRead(instruction, register, stateType, kindType) }
                    ?: return@mapNotNull null
                TagIndicatorBind(classDef.type, method.name, parameters, index, stateType, field, kindType)
            }
        }

/** The name of the [kindType] field of [stateType] that [instruction] reads from [register], else null. */
private fun kindRead(instruction: Instruction, register: Int, stateType: String, kindType: String): String? {
    if (instruction.opcode != Opcode.IGET_OBJECT || (instruction as TwoRegisterInstruction).registerB != register) return null
    val field = (instruction as ReferenceInstruction).reference as FieldReference
    return field.name.takeIf { field.definingClass == stateType && field.type == kindType }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: the tag icon: $detail")

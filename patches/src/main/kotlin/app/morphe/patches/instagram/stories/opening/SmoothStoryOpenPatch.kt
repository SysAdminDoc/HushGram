/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.opening

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Smooth story opening"
internal const val SMOOTH_STORY_OPEN = "$EXTENSION_PACKAGE/stories/SmoothStoryOpen;"
internal const val EASE_STIFFNESS = "$SMOOTH_STORY_OPEN->stiffness(F)F"
internal const val EASE_DAMPING = "$SMOOTH_STORY_OPEN->damping(F)F"

/** What the story viewer's animator logs when it grows a story out of its ring. Redex keeps the text. */
internal const val ANIMATE_REVEAL = "ReelViewerAnimator.animateReveal"

/** What AndroidX's SpringForce throws from its stiffness setter and from its damping setter. */
internal const val STIFFNESS_CHECK = "Spring stiffness constant must be positive."
internal const val DAMPING_CHECK = "Damping ratio must be non-negative"

/**
 * Slows the spring that grows a story out of its ring in the stories row, and takes the bounce out
 * of it. Off to start: Instagram's own is quick and a little springy.
 */
@Suppress("unused")
val smoothStoryOpenPatch = bytecodePatch(
    name = "Smooth story opening",
    description = "Opens a story from the stories row with a slower, gentler zoom instead of Instagram's quick " +
        "spring. Starts off. Turn it on in HushGram settings > Stories.",
) {
    category("Stories")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("smoothStoryOpen")
        easeStorySpring(findStorySpring())
        enableStatus("smoothStoryOpen")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** One call that hands the spring a float: the method it's in, the call's index and the float's register. */
internal class SpringCall(val name: String, val parameters: List<String>, val returnType: String, val at: Int, val register: Int)

/** The story viewer's animator and its two calls into the spring, the stiffness first. */
internal class StorySpring(val type: String, val stiffness: SpringCall, val damping: SpringCall)

/**
 * Finds the story viewer's animator, the one class that loads [ANIMATE_REVEAL] and sets a spring's
 * stiffness and damping, and its one call of each setter. The setters are the (F)V methods that
 * load AndroidX's own checks, [STIFFNESS_CHECK] and [DAMPING_CHECK]. Fails before anything changes
 * when the animator, a setter or a single call of each is missing, or when something jumps onto a
 * call, where its hook goes.
 */
internal fun BytecodePatchContext.findStorySpring(): StorySpring {
    val stiffnessSetters = settersLoading(STIFFNESS_CHECK, "stiffness")
    val dampingSetters = settersLoading(DAMPING_CHECK, "damping ratio")
    val animators = classesLoadingString(ANIMATE_REVEAL).filter { classDef ->
        classDef.methods.any { method -> callsTo(method, stiffnessSetters).isNotEmpty() }
    }
    val animator = animators.singleOrNull()
        ?: refuse("expected one class loading \"$ANIMATE_REVEAL\" that sets a spring's stiffness, found ${animators.size}")
    fun only(setters: Set<String>, what: String): SpringCall {
        val calls = animator.methods.flatMap { method -> callsTo(method, setters).map { at -> method to at } }
        val (method, at) = calls.singleOrNull()
            ?: refuse("expected ${animator.type} to set its spring's $what once, found ${calls.size}")
        val where = "${animator.type}->${method.name}"
        if (at in method.jumpTargets()) refuse("something jumps onto the $what call in $where")
        val register = when (val call = method.implementation!!.instructions.elementAt(at)) {
            is FiveRegisterInstruction -> call.registerD
            is RegisterRangeInstruction -> call.startRegister + 1
            else -> refuse("the $what call in $where takes its float in a way this patch hasn't seen")
        }
        if (register > 255) refuse("the $what call in $where takes its float in v$register, past v255")
        return SpringCall(method.name, method.parameterTypes.map(CharSequence::toString), method.returnType, at, register)
    }
    return StorySpring(animator.type, only(stiffnessSetters, "stiffness"), only(dampingSetters, "damping ratio"))
}

/** The (F)V methods loading [check], as "type->name", refusing when there are none. */
private fun BytecodePatchContext.settersLoading(check: String, what: String): Set<String> {
    val setters = classesLoadingString(check).flatMap { classDef ->
        classDef.methods.filter { method ->
            method.returnType == "V" && method.parameterTypes.map(CharSequence::toString) == listOf("F") &&
                loadsString(method, check)
        }.map { "${classDef.type}->${it.name}" }
    }.toSet()
    if (setters.isEmpty()) refuse("no spring $what setter loads \"$check\"")
    return setters
}

/** The indexes of [method]'s virtual calls into one of [setters]. */
private fun callsTo(method: Method, setters: Set<String>): List<Int> =
    method.implementation?.instructions?.withIndex()?.filter { (_, instruction) ->
        val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        called != null && (instruction.opcode == Opcode.INVOKE_VIRTUAL || instruction.opcode == Opcode.INVOKE_VIRTUAL_RANGE) &&
            "${called.definingClass}->${called.name}" in setters &&
            called.parameterTypes.map(CharSequence::toString) == listOf("F") && called.returnType == "V"
    }?.map { it.index } ?: emptyList()

/**
 * Right before each call, passes the float it hands the spring through the extension, which eases
 * it while the switch is on. The later call goes in first, so the earlier one's index still holds.
 */
internal fun BytecodePatchContext.easeStorySpring(spring: StorySpring) {
    val calls = listOf(spring.stiffness to EASE_STIFFNESS, spring.damping to EASE_DAMPING)
    for ((call, hook) in calls.sortedByDescending { (call, _) -> call.at }) {
        val method = mutableClassDefBy(spring.type).methods.single {
            it.name == call.name && it.returnType == call.returnType &&
                it.parameterTypes.map(CharSequence::toString) == call.parameters
        }
        method.addInstructions(
            call.at,
            """
                invoke-static/range { v${call.register} .. v${call.register} }, $hook
                move-result v${call.register}
            """,
        )
    }
}

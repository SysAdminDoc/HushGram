/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.savedrings

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.classesReadingType
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Story rings on saved posts"
internal const val SAVED_STORY_RINGS = "$EXTENSION_PACKAGE/stories/SavedStoryRings;"
internal const val SAVED_RING_SHOWS = "$SAVED_STORY_RINGS->shows(Ljava/lang/Object;)Z"

private const val ENUM = "Ljava/lang/Enum;"
private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"

/** The surface names the saved posts' feed shows under, which the extension lets through. */
internal val SAVED_SURFACES = listOf("SAVE_FEED", "SAVE_HOME")

/** Names only the post surface enum holds together: Home, Explore, the saved feed and the Reels viewer. */
internal val POST_SURFACE_NAMES = listOf("MAIN_FEED", "EXPLORE_FEED", "SAVE_FEED", "SAVE_HOME", "CLIPS_VIEWER")

/** Fewest surfaces the ring lookup checks by name before it reads the author's story (Home, Explore and three more on 456.1). */
private const val FEWEST_ALLOWED = 3

/**
 * Where a post's header asks for the author's story (#790): in [type]->[name], the read at [read]
 * of the post's surface into [surface], then a run of comparisons against the surfaces allowed a
 * ring, whose match jumps to the lookup at [lookup]. The saved posts' feed isn't among them, so a
 * post you saved shows no ring and its picture opens the profile.
 */
internal class SavedRingSite(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val read: Int,
    val surface: Int,
    val lookup: Int,
) {
    val where get() = "$type->$name"
}

/**
 * Shows the story ring on posts you saved (#790). Instagram's lookup of a post author's story,
 * which the header's ring and a tap on the picture both go through, answers only on Home, Explore,
 * a single post, a post's own feed and comments. The surface it reads goes to the extension first,
 * and a yes, given only for the saved feed with the switch on, jumps to that lookup as an allowed
 * surface's match does. Instagram's own checks after it stay, and the story comes from what the
 * app already holds, with no request of its own. Starts off.
 */
@Suppress("unused")
val savedStoryRingsPatch = bytecodePatch(
    name = "Story rings on saved posts",
    description = "Shows the story ring around the author's picture on posts you saved, as Home does, and a tap on " +
        "it opens their story. It only uses stories Instagram has already loaded. Starts off. Turn it on in " +
        "HushGram settings > Stories.",
) {
    category("Stories")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("savedStoryRings")
        requireSavedRingHook()
        val site = findSavedRingSite()
        letSavedRingsThrough(site)
        enableStatus("savedStoryRings")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** Checks the extension has the static shows(Object)Z before anything changes. */
internal fun BytecodePatchContext.requireSavedRingHook() {
    val extension = classDefByOrNull(SAVED_STORY_RINGS) ?: refuse("the extension has no $SAVED_STORY_RINGS")
    val hook = extension.methods.singleOrNull {
        it.name == "shows" && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" &&
            it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;")
    }
    if (hook == null) refuse("the extension has no static shows(Ljava/lang/Object;)Z")
}

/**
 * The post surface enum: the one enum whose static initializer names every one of
 * [POST_SURFACE_NAMES]. Refuses when there isn't exactly one.
 */
internal fun BytecodePatchContext.findPostSurfaceEnum(): String {
    val enums = classesLoadingString(SAVED_SURFACES.first()).filter { it.isPostSurfaceEnum(this) }
    return enums.singleOrNull()?.type
        ?: refuse("expected one enum naming ${POST_SURFACE_NAMES.joinToString()}, found ${enums.size}: ${enums.map { it.type }}")
}

private fun ClassDef.isPostSurfaceEnum(context: BytecodePatchContext): Boolean {
    if (superclass != ENUM) return false
    val initializer = methods.singleOrNull { it.name == "<clinit>" } ?: return false
    val code = initializer.code()
    val names = code.indices.mapNotNullTo(HashSet()) { context.stringLoadedAt(code, it) }
    return names.containsAll(POST_SURFACE_NAMES)
}

/**
 * The lookup of a post author's story: among the methods reading a field of the surface enum, the
 * one (UserSession, props, user, Function0, boolean, boolean) returning an object that reads the
 * props' surface once, compares it with at least [FEWEST_ALLOWED] of the enum's constants, and
 * whose first match jumps to a static (UserSession, user) call returning what the method returns.
 * Refuses when there isn't exactly one.
 */
internal fun BytecodePatchContext.findSavedRingSite(): SavedRingSite {
    val enum = findPostSurfaceEnum()
    val sites = classesReadingType(enum).flatMap { owner ->
        owner.methods.filter { it.hasLookupShape() }.mapNotNull { it.ringSite(enum) }
    }
    return sites.singleOrNull()
        ?: refuse("expected one story lookup gated on the post surface $enum, found ${sites.size}: ${sites.map { it.where }}")
}

private fun Method.hasLookupShape(): Boolean {
    if (implementation == null || AccessFlags.ABSTRACT.isSet(accessFlags) || !returnType.startsWith("L")) return false
    val parameters = parameterTypes.map(Any::toString)
    return parameters.size == 6 && parameters[0] == USER_SESSION && parameters[1].startsWith("L") &&
        parameters[2].startsWith("L") && parameters[3] == FUNCTION0 && parameters[4] == "Z" && parameters[5] == "Z"
}

private fun Method.ringSite(enum: String): SavedRingSite? {
    val code = code()
    val props = parameterTypes[1].toString()
    val reads = code.indices.filter { at ->
        code[at].opcode == Opcode.IGET_OBJECT && (code[at].reference() as? FieldReference)?.let { it.definingClass == props && it.type == enum } == true
    }
    val read = reads.singleOrNull() ?: return null
    val surface = (code[read] as OneRegisterInstruction).registerA
    var at = read + 1
    var compared = 0
    var firstMatch = -1
    while (at + 1 < code.size) {
        val load = code[at]
        val compare = code[at + 1]
        if (load.opcode != Opcode.SGET_OBJECT || (load.reference() as? FieldReference)?.let { it.definingClass == enum && it.type == enum } != true) break
        if (compare.opcode != Opcode.IF_EQ && compare.opcode != Opcode.IF_NE) break
        val loaded = (load as OneRegisterInstruction).registerA
        val pair = compare as TwoRegisterInstruction
        if (setOf(pair.registerA, pair.registerB) != setOf(surface, loaded)) break
        if (firstMatch < 0 && compare.opcode == Opcode.IF_EQ) firstMatch = at + 1
        compared++
        at += 2
    }
    if (compared < FEWEST_ALLOWED || firstMatch < 0) return null
    val lookup = ControlFlow.of(this).normal[firstMatch].firstOrNull() ?: return null
    val called = code.getOrNull(lookup)?.takeIf { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
        ?.reference() as? MethodReference ?: return null
    if (called.parameterTypes.map(Any::toString) != listOf(USER_SESSION, parameterTypes[2].toString()) || called.returnType != returnType) return null
    return SavedRingSite(definingClass, name, parameterTypes.map(Any::toString), returnType, read, surface, lookup)
}

/**
 * Right after [site]'s read of the surface, hands it to [SAVED_RING_SHOWS] and jumps to the lookup
 * on a yes. A low local nothing reads after the read carries the answer.
 */
internal fun BytecodePatchContext.letSavedRingsThrough(site: SavedRingSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.returnType == site.returnType && it.parameterTypes.map(Any::toString) == site.parameters
    }
    val at = site.read + 1
    if (at in method.jumpTargets()) refuse("${site.where} jumps to just past the surface read, where the hook would go")
    val answer = method.freeLocalsAt(PATCH, at, 1, targets = listOf(site.lookup), highest = 255, except = listOf(site.surface)).single()
    method.addInstructionsWithLabels(
        at,
        """
            invoke-static/range { v${site.surface} .. v${site.surface} }, $SAVED_RING_SHOWS
            move-result v$answer
            if-nez v$answer, :saved_ring
        """,
        ExternalLabel("saved_ring", method.getInstruction(site.lookup)),
    )
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()
private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

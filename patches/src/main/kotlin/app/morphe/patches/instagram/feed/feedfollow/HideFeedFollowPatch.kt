/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.feedfollow

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val FEED_FOLLOW_STATUS = "feedFollow"
internal const val FEED_SUBSCRIBE = "$EXTENSION_PACKAGE/feed/FeedFollowButton;->showSubscribe(I)Z"
internal const val FEED_HEADER_STATE = "null cannot be cast to non-null type instagram.features.feed.ui.rows.mediaheader.domain.uicontract.MediaHeaderUiState"
private const val SUBSCRIBE_TAP = "ig_fan_club_subscribe_cta_tap"
private const val SUBSCRIBE_IMPRESSION = "ig_fan_club_subscribe_cta_impression"

/** The native eligibility answer immediately before the branch omitting just Subscribe. */
internal class FeedSubscribeSite(val method: Method, val branch: Int, val answer: Int, val field: String)

private fun refuse(detail: String): Nothing = throw PatchException("Hide Follow on feed posts: $detail")

/**
 * Subscribe is an inlined Litho child of the feed header, with no Subscribe view id. Its click
 * calls the subscription tap logger and casts its owner to this header. The header creates that
 * click once, then a button whose first argument is an OnClickListener. A two-boolean native
 * state gates it: false assigns null to that button and rejoins immediately after its constructor.
 * Filter that answer without changing the state or any generic button renderer.
 */
internal fun BytecodePatchContext.findFeedSubscribe(): FeedSubscribeSite? {
    val headers = classesLoadingString(FEED_HEADER_STATE).flatMap { owner ->
        owner.methods.filter { loadsString(it, FEED_HEADER_STATE) }
    }
    if (headers.isEmpty()) {
        patchLog.warning("Hide Follow on feed posts: no feed header state; Subscribe keeps its native button.")
        return null
    }
    val header = headers.singleOrNull() ?: refuse("expected one feed header state, found ${headers.size}")
    val logger = classesLoadingString(SUBSCRIBE_TAP).singleOrNull { owner ->
        owner.methods.any { loadsString(it, SUBSCRIBE_IMPRESSION) }
    } ?: refuse("expected one subscription tap/impression logger")
    val tap = logger.methods.singleOrNull {
        loadsString(it, SUBSCRIBE_TAP) && it.returnType == "V" &&
            it.parameterTypes.map(CharSequence::toString) == List(3) { "Ljava/lang/String;" }
    } ?: refuse("expected one subscription tap method")
    val clicks = classesCalling(logger.type, tap.name).filter { owner ->
        owner.methods.any { method ->
            method.name == "onClick" && method.returnType == "V" &&
                method.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;") &&
                method.code().any { it.opcode == Opcode.CHECK_CAST && it.reference()?.toString() == header.definingClass } &&
                method.code().any { it.reference()?.toString() == tap.toString() }
        }
    }
    val click = clicks.singleOrNull()?.type ?: refuse("expected one subscription click owned by the feed header")
    val code = header.code()
    // 32-bit builds share one switch listener between several header buttons and choose the
    // subscription case by an int argument; the button is still gated by a state boolean read
    // right before its branch. The dedicated click 64-bit builds use is tried first.
    fun sharedFeedSubscribe(): FeedSubscribeSite {
        val marker = code.indices.firstOrNull { stringLoadedAt(code, it) == FEED_HEADER_STATE }
            ?: refuse("expected the feed header to load its state name")
        val state = (marker + 1 until minOf(code.size, marker + 8)).mapNotNull { at ->
            code[at].takeIf { it.opcode == Opcode.CHECK_CAST }?.let { (it as ReferenceInstruction).reference.toString() }
        }.firstOrNull() ?: refuse("expected the feed header to cast its state")
        fun shareCheck(create: Int): FeedSubscribeSite? {
            for (at in create - 1 downTo maxOf(0, create - 40)) {
                val branch = code[at]
                if (branch.opcode != Opcode.IF_EQZ) continue
                val read = code.getOrNull(at - 1) ?: continue
                val field = (read as? ReferenceInstruction)?.reference as? FieldReference ?: continue
                if (read.opcode != Opcode.IGET_BOOLEAN || field.type != "Z" || field.definingClass != state) continue
                return FeedSubscribeSite(header, at, (branch as OneRegisterInstruction).registerA, field.toString())
            }
            return null
        }
        return code.indices.filter { code[it].opcode == Opcode.NEW_INSTANCE && code[it].reference()?.toString() == click }
            .firstNotNullOfOrNull(::shareCheck)
            ?: refuse("expected one native eligibility branch omitting only Subscribe")
    }
    val flow by lazy {
        try { ControlFlow.of(header) } catch (failure: IllegalArgumentException) {
            refuse("unreadable feed header control flow: ${failure.message}")
        }
    }
    // 453's 32-bit builds build the shared click twice, for Subscribe and another header button, so
    // each one is tried and only the one gated as Subscribe is gated counts.
    fun classicAt(create: Int): FeedSubscribeSite? {
        val constructors = (create + 1 until minOf(code.size, create + 22)).filter { at ->
            val call = code[at].reference() as? MethodReference
            call?.name == "<init>" && call.returnType == "V" && call.parameterTypes.size == 6 &&
                call.parameterTypes.first().toString() == "Landroid/view/View\$OnClickListener;" &&
                call.parameterTypes.takeLast(2).map(CharSequence::toString) == listOf("Ljava/lang/String;", "Z")
        }
        val constructor = constructors.singleOrNull() ?: return null
        val button = (code[constructor].reference() as MethodReference).definingClass
        val made = (create + 1 until constructor).singleOrNull {
            code[it].opcode == Opcode.NEW_INSTANCE && (code[it].reference() as? TypeReference)?.type == button
        } ?: return null
        val result = (code[made] as OneRegisterInstruction).registerA
        val checks = (maxOf(1, create - 18) until create - 1).filter { at ->
            val read = code[at] as? TwoRegisterInstruction ?: return@filter false
            val field = code[at].reference() as? FieldReference ?: return@filter false
            val state = code[at - 1] as? TwoRegisterInstruction ?: return@filter false
            val stateField = code[at - 1].reference() as? FieldReference ?: return@filter false
            val branch = code[at + 1] as? OneRegisterInstruction ?: return@filter false
            if (code[at].opcode != Opcode.IGET_BOOLEAN || field.type != "Z" ||
                code[at - 1].opcode != Opcode.IGET_OBJECT || state.registerA != read.registerB || stateField.type != field.definingClass ||
                code[at + 1].opcode != Opcode.IF_EQZ || branch.registerA != read.registerA) return@filter false
            val stateClass = classDefByOrNull(field.definingClass) ?: return@filter false
            if (stateClass.fields.count { it.type == "Z" && !AccessFlags.STATIC.isSet(it.accessFlags) } != 2) return@filter false
            val skip = flow.normal[at + 1].singleOrNull { it != at + 2 } ?: return@filter false
            val zero = code[skip]
            zero.opcode in setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST) &&
                (zero as OneRegisterInstruction).registerA == result && (zero as NarrowLiteralInstruction).narrowLiteral == 0 &&
                code.getOrNull(skip + 1)?.opcode in setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32) &&
                flow.normal[skip + 1].singleOrNull() == constructor + 1
        }
        val check = checks.singleOrNull() ?: return null
        return FeedSubscribeSite(header, check + 1, (code[check] as OneRegisterInstruction).registerA, code[check].reference().toString())
    }
    val classic = code.indices.filter { code[it].opcode == Opcode.NEW_INSTANCE && code[it].reference()?.toString() == click }
        .mapNotNull { create -> runCatching { classicAt(create) }.getOrNull() }.singleOrNull()
    val site = classic ?: sharedFeedSubscribe()
    val hookOwner = classDefByOrNull(FEED_SUBSCRIBE.substringBefore("->")) ?: refuse("missing Subscribe extension")
    if (hookOwner.methods.none {
        it.toString() == FEED_SUBSCRIBE && AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
    }) refuse("missing public static $FEED_SUBSCRIBE")
    return site
}

internal fun BytecodePatchContext.hideFeedSubscribe(site: FeedSubscribeSite) {
    val method = mutableClassDefBy(site.method.definingClass).methods.single {
        it.name == site.method.name && it.parameterTypes == site.method.parameterTypes && it.returnType == site.method.returnType
    }
    val code = method.code()
    if (code[site.branch].opcode != Opcode.IF_EQZ || (code[site.branch] as OneRegisterInstruction).registerA != site.answer ||
        code[site.branch - 1].reference()?.toString() != site.field) refuse("feed Subscribe eligibility changed before injection")
    method.addInstructionsAtControlFlowLabel(site.branch, """
        invoke-static/range { v${site.answer} .. v${site.answer} }, $FEED_SUBSCRIBE
        move-result v${site.answer}
    """.trimIndent())
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

/**
 * Takes the Follow and Subscribe buttons off the top of feed posts (#248). See FeedFollowButton in
 * the extension.
 *
 * The extension hides ordinary Follow views by their ids within a feed header. Subscribe's native
 * header eligibility is filtered separately, so the Litho button isn't built. The same controls in
 * a story, a reel or a profile stay. In the default selection with its switch off.
 */
@Suppress("unused")
val hideFeedFollowPatch = bytecodePatch(
    name = "Hide Follow on feed posts",
    description = "Takes the Follow and Subscribe buttons off the top of posts in your feed. The name, time and menu " +
        "stay. Starts off. Turn it on in HushGram settings > Feed.",
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(FEED_FOLLOW_STATUS)
        findFeedSubscribe()?.let { hideFeedSubscribe(it) }
        enableStatus(FEED_FOLLOW_STATUS)
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.gridjump

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.download.profile.profileBarFills
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.profile.postslist.PROFILE_MEDIA_TAB
import app.morphe.patches.instagram.profile.postslist.findPostsTabResume
import app.morphe.patches.instagram.profile.threads.PROFILE_ACTION_BAR
import app.morphe.patches.instagram.profile.threads.USER_DETAIL
import app.morphe.patches.instagram.reels.origin.afterSuperResume
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val GRID_JUMP_PATCH = "Jump a profile's posts to a date"
internal const val GRID_JUMP = "$EXTENSION_PACKAGE/profile/GridJump;"
internal const val JUMP_RESUMED = "$GRID_JUMP->resumed(Ljava/lang/Object;)V"
internal const val JUMP_BOUND = "$GRID_JUMP->bound(Landroid/view/View;)V"
internal const val JUMP_MAX_ID = "$GRID_JUMP->maxId(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"
internal const val JUMP_RELOAD = "reload"

/** The request a profile's grid pages through, and the keys of the two values the jump reads and sets. */
internal const val TIMELINE_QUERY = "IGProfileTimelineQuery"
internal const val MAX_ID_KEY = "max_id"
internal const val USER_ID_KEY = "user_id"

private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"

/**
 * What the jump hooks, found before anything changes. [request] is the grid's request with the
 * parameters holding the [user] id and the [maxId], as numbers of its p registers. [load] is the
 * profile screen's call for a tab's posts, reached from the tab through its [holder] and given its
 * [feed].
 */
internal class GridJumpSite(
    val request: Method,
    val user: Int,
    val maxId: Int,
    val load: Method,
    val holder: Field,
    val screen: Field,
    val feed: Field,
    val bar: Method,
)

private fun refuse(why: String): Nothing = throw PatchException("$GRID_JUMP_PATCH: $why")

/**
 * Jumps a profile's posts grid to a day. Asks Instagram for the grid's first page again with a max_id
 * made up for the end of that day, one request for each jump, the same request scrolling sends. The
 * server decides what it answers: it's Instagram's own paging, with no list of posts built here. In
 * the default selection with its switch off.
 */
@Suppress("unused")
val profileGridJumpPatch = bytecodePatch(
    name = "Jump a profile's posts to a date",
    description = "Puts a calendar on a profile's top bar. Pick a day and the posts grid goes back to that day, " +
        "with older posts as you scroll. Pull down for the newest again. Starts off. Turn it on in HushGram " +
        "settings > Profiles.",
) {
    category("Profiles")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("profileGridJump")
        applyGridJump(findGridJump())
        enableStatus("profileGridJump")
    }
}

/** Finds everything the jump needs and proves the extension has its methods, before any edit. */
internal fun BytecodePatchContext.findGridJump(): GridJumpSite {
    val extension = classDefByOrNull(GRID_JUMP) ?: refuse("the extension has no $GRID_JUMP")
    for (hook in listOf(JUMP_RESUMED, JUMP_BOUND, JUMP_MAX_ID)) {
        if (extension.methods.none {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook &&
                    AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
            }
        ) refuse("the extension has no public static $hook")
    }
    if (extension.methods.none { it.isReloadStub() }) refuse("the extension has no $JUMP_RELOAD to fill in")

    val requests = classesLoadingString(TIMELINE_QUERY).flatMap { host ->
        host.methods.filter { loadsString(it, TIMELINE_QUERY) && loadsString(it, MAX_ID_KEY) && loadsString(it, USER_ID_KEY) }
    }
    val request = requests.singleOrNull() ?: refuse("expected one $TIMELINE_QUERY request taking $MAX_ID_KEY and $USER_ID_KEY, found ${requests.size}")
    if (!AccessFlags.STATIC.isSet(request.accessFlags)) refuse("the $TIMELINE_QUERY request isn't static")
    if (0 in request.jumpTargets()) refuse("a jump enters the $TIMELINE_QUERY request at its first instruction")
    val implementation = request.implementation ?: refuse("the $TIMELINE_QUERY request has no body")
    val words = request.parameterTypes.sumOf { wordsOf(it.toString()) }
    if (implementation.registerCount - words < 2) refuse("the $TIMELINE_QUERY request has fewer than two locals for the hook")
    val user = parameterHolding(request, USER_ID_KEY)
    val maxId = parameterHolding(request, MAX_ID_KEY)
    // The hook writes the max_id back with move-object/from16, whose target is eight bits.
    if (implementation.registerCount - words + maxId > 255) refuse("the $TIMELINE_QUERY request keeps $MAX_ID_KEY past v255")

    val screen = classDefByOrNull(USER_DETAIL) ?: refuse("this Instagram build has no $USER_DETAIL")
    val loads = screen.methods.filter { it.asksForTabPosts() }
    val load = loads.singleOrNull() ?: refuse("expected one method of $USER_DETAIL asking for a tab's posts, found ${loads.size}")
    val tab = classDefByOrNull(PROFILE_MEDIA_TAB) ?: refuse("this Instagram build has no $PROFILE_MEDIA_TAB")
    val feedType = load.parameterTypes.first().toString()
    val feeds = tab.fields.filter { it.type == feedType && it.isOpenField() }
    val feed = feeds.singleOrNull() ?: refuse("expected one public $feedType field on the posts tab, found ${feeds.size}")
    val holders = tab.fields.filter { it.isOpenField() }.mapNotNull { field ->
        screenFieldOf(field.type)?.let { field to it }
    }
    val (holder, screenField) = holders.singleOrNull() ?: refuse("expected one public field of the posts tab holding the profile screen, found ${holders.size}")

    val barClass = classDefByOrNull(PROFILE_ACTION_BAR) ?: refuse("this Instagram build has no $PROFILE_ACTION_BAR")
    val binders = barClass.methods.filter { it.profileBarFills().isNotEmpty() }
    val binder = binders.singleOrNull() ?: refuse("expected one method of $PROFILE_ACTION_BAR filling its rows, found ${binders.size}")
    val fill = binder.profileBarFills().singleOrNull() ?: refuse("expected one call filling the rows in ${binder.name}")
    if (fill.at + 1 in binder.jumpTargets()) refuse("something in ${binder.name} jumps to just past the call filling the rows")

    // Both refuse on what the tab hook needs, the extension's PostsList among it.
    findPostsTabResume().afterSuperResume()

    return GridJumpSite(request, user, maxId, load, holder, screenField, feed, binder)
}

/**
 * Only called once [findGridJump] found everything. The request's max_id goes through the extension
 * first thing, with the two locals free at its entry. The tab and the bar's right row go to the
 * extension behind the hooks of Profile posts as a list and Profile extras, which keep the first spot
 * in those methods. The reload's stub is written from the arguments the extension passes as spare.
 */
internal fun BytecodePatchContext.applyGridJump(site: GridJumpSite) {
    mutableClassDefBy(site.request.definingClass).methods.single { it.isSameAs(site.request) }.addInstructions(
        0,
        """
            move-object/from16 v0, p${site.user}
            move-object/from16 v1, p${site.maxId}
            invoke-static { v0, v1 }, $JUMP_MAX_ID
            move-result-object v0
            move-object/from16 p${site.maxId}, v0
        """,
    )

    val resume = findPostsTabResume()
    resume.addInstructions(resume.afterSuperResume(), "invoke-static/range { p0 .. p0 }, $JUMP_RESUMED")

    val binder = mutableClassDefBy(PROFILE_ACTION_BAR).methods.single { it.isSameAs(site.bar) }
    // Read off the code as it stands, since Profile extras' entry hook may have gone in first.
    val fill = binder.profileBarFills().single()
    binder.addInstructions(fill.at + 1, "invoke-static/range { v${fill.right} .. v${fill.right} }, $JUMP_BOUND")

    val load = site.load
    mutableClassDefBy(GRID_JUMP).methods.single { it.isReloadStub() }.addInstructions(
        0,
        """
            check-cast p0, $PROFILE_MEDIA_TAB
            iget-object p1, p0, ${site.holder.spelled()}
            iget-object p2, p1, ${site.screen.spelled()}
            iget-object p1, p0, ${site.feed.spelled()}
            const/4 p3, 0x1
            invoke-virtual { p2, p1, p3, p3 }, ${load.definingClass}->${load.name}(${load.parameterTypes.joinToString("")})V
        """,
    )
}

/** The class [type]'s one public field holding the profile screen, when the extension can reach it. */
private fun BytecodePatchContext.screenFieldOf(type: String): Field? {
    if (!type.startsWith("L")) return null
    val holder = classDefByOrNull(type) ?: return null
    if (!AccessFlags.PUBLIC.isSet(holder.accessFlags)) return null
    return holder.fields.filter { it.type == USER_DETAIL && it.isOpenField() }.singleOrNull()
}

/**
 * Which parameter of [method] it files under [key]: the call that takes the key's register, whose last
 * argument is the value, followed back through plain moves to a parameter's register. Answered as the
 * number of its p register.
 */
private fun BytecodePatchContext.parameterHolding(method: Method, key: String): Int {
    val code = method.implementation?.instructions?.toList().orEmpty()
    val loads = code.indices.filter { stringLoadedAt(code, it) == key }
    val load = loads.singleOrNull() ?: refuse("expected the request to load $key once, found ${loads.size}")
    // A const-string holds the key itself, a pool's call hands it to the move-result after it.
    val keyAt = if (code[load].opcode == Opcode.INVOKE_STATIC) load + 1 else load
    val keyRegister = (code.getOrNull(keyAt) as? OneRegisterInstruction)?.registerA ?: refuse("the request's $key isn't held in a register")
    val call = (keyAt + 1 until code.size).firstOrNull { keyRegister in code[it].arguments() }
        ?: refuse("nothing in the request takes $key")
    var held = code[call].arguments().last()
    for (at in call - 1 downTo 0) {
        val step = code[at]
        if (!step.writes(held)) continue
        if (step.opcode != Opcode.MOVE_OBJECT && step.opcode != Opcode.MOVE_OBJECT_FROM16 && step.opcode != Opcode.MOVE_OBJECT_16) {
            refuse("the request's $key value isn't one of its parameters")
        }
        held = (step as TwoRegisterInstruction).registerB
    }
    val first = (method.implementation?.registerCount ?: 0) - method.parameterTypes.sumOf { wordsOf(it.toString()) }
    val number = held - first
    if (number < 0) refuse("the request's $key value isn't one of its parameters")
    var word = 0
    for (type in method.parameterTypes.map { it.toString() }) {
        if (word == number) {
            if (type != STRING) refuse("the request's $key parameter is a $type")
            return number
        }
        word += wordsOf(type).toInt()
    }
    refuse("the request's $key value isn't one of its parameters")
}

private fun wordsOf(type: String): Int = if (type == "J" || type == "D") 2 else 1

private fun Method.isReloadStub(): Boolean =
    name == JUMP_RELOAD && returnType == "V" && AccessFlags.STATIC.isSet(accessFlags) && parameterTypes.map { it.toString() } == List(4) { OBJECT }

/** The screen's call for a tab's posts: (tab, pull, first page), ending in the feed's load with a count and those two. */
private fun Method.asksForTabPosts(): Boolean {
    val parameters = parameterTypes.map { it.toString() }
    if (returnType != "V" || !AccessFlags.PUBLIC.isSet(accessFlags) || AccessFlags.STATIC.isSet(accessFlags)) return false
    if (parameters.size != 3 || !parameters[0].startsWith("L") || parameters[1] != "Z" || parameters[2] != "Z") return false
    return implementation?.instructions?.any { step ->
        val call = (step as? ReferenceInstruction)?.reference as? MethodReference
        (step.opcode == Opcode.INVOKE_INTERFACE || step.opcode == Opcode.INVOKE_INTERFACE_RANGE) && call != null && call.returnType == "V" &&
            call.parameterTypes.map { it.toString() }.takeLast(3) == listOf("I", "Z", "Z")
    } == true
}

private fun Field.isOpenField(): Boolean = AccessFlags.PUBLIC.isSet(accessFlags) && !AccessFlags.STATIC.isSet(accessFlags)
private fun Field.spelled(): String = "$definingClass->$name:$type"
private fun Method.isSameAs(other: Method): Boolean =
    name == other.name && returnType == other.returnType && parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }

private fun Instruction.arguments(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val written = (this as? OneRegisterInstruction)?.registerA ?: return false
    return written == register || (opcode.setsWideRegister() && written + 1 == register)
}

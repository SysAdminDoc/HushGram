/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.gridjump

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
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
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val GRID_JUMP_PATCH = "Jump a profile's posts to a date"
internal const val GRID_JUMP = "$EXTENSION_PACKAGE/profile/GridJump;"
internal const val JUMP_RESUMED = "$GRID_JUMP->resumed(Ljava/lang/Object;)V"
internal const val JUMP_BOUND = "$GRID_JUMP->bound(Landroid/view/View;)V"
internal const val JUMP_REST_MAX_ID = "$GRID_JUMP->restMaxId(Ljava/lang/Object;Ljava/lang/String;ILjava/lang/String;I)Ljava/lang/String;"
internal const val JUMP_REST_PATH = "$GRID_JUMP->restPath(Ljava/lang/String;)Ljava/lang/String;"
internal const val JUMP_RELOAD = "reload"

/** What marks the profile's fetch of a page, and the path of the request the jump borrows. */
internal const val REST_SOURCE = "rest_network"
internal const val REST_SOURCE_KEY = "profile_data_source"
internal const val TAGGED_POSTS_PATH = "usertags/%s/feed/"

/** How many instructions the hook puts first in the load. */
internal const val ENTRY_HOOK_SIZE = 25

/** The registers the load's hook writes, v0 to v8, and how far back from the request its reads sit. */
private const val ENTRY_LOCALS = 9
private const val ROUTE_WINDOW = 10

private const val STRING = "Ljava/lang/String;"
private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val OBJECT = "Ljava/lang/Object;"

/**
 * What the jump hooks, found before anything changes. [rest] is the route it takes. [load] is the
 * profile screen's call for a tab's posts, reached from the tab through its [holder] and given its
 * [feed].
 */
internal class GridJumpSite(
    val rest: GridJumpRest,
    val load: Method,
    val holder: Field,
    val screen: Field,
    val feed: Field,
    val bar: Method,
)

/**
 * The route the jump borrows from the tagged posts tab. [entry] is the load picking a route for a
 * page. For a user, read through [userId], it asks the [request] with the [session], the user's id
 * and the cursor read through [state], [paging] and [cursor], then hands that to [fetch]. [free] says
 * whether the paging state can load now. The request's path is in [pathRegister] by [pathAt].
 */
internal class GridJumpRest(
    val entry: Method,
    val fetch: Method,
    val request: Method,
    val pathAt: Int,
    val pathRegister: Int,
    val userId: FieldReference,
    val state: MethodReference,
    val paging: FieldReference,
    val cursor: FieldReference,
    val free: Method,
    val session: FieldReference,
)

private fun refuse(why: String): Nothing = throw PatchException("$GRID_JUMP_PATCH: $why")

/**
 * Jumps a profile's posts grid to a day. The grid's own query ignores max_id, so a jump and the pages
 * after it take the route the same screen keeps for tagged posts, pointed at the user's own feed, with
 * a max_id made up for the end of that day. That's one plain request for each jump or page, at
 * scrolling's pace. The server decides what it answers: it's Instagram's own paging, with no list of
 * posts built here. In the default selection with its switch off.
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
    for (hook in listOf(JUMP_RESUMED, JUMP_BOUND, JUMP_REST_MAX_ID, JUMP_REST_PATH)) {
        if (extension.methods.none {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook &&
                    AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
            }
        ) refuse("the extension has no public static $hook")
    }
    if (extension.methods.none { it.isReloadStub() }) refuse("the extension has no $JUMP_RELOAD to fill in")

    val rest = findGridJumpRest()

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

    return GridJumpSite(rest, load, holder, screenField, feed, binder)
}

/**
 * The route's parts, read off the load's tagged posts branch: the one request call handing its answer
 * straight to the fetch. The reads before that call say where the user's id, the session, the paging
 * state and its cursor are.
 */
private fun BytecodePatchContext.findGridJumpRest(): GridJumpRest {
    val fetches = classesLoadingString(REST_SOURCE).flatMap { host ->
        host.methods.filter { loadsString(it, REST_SOURCE) && loadsString(it, REST_SOURCE_KEY) }.map { host to it }
    }
    val (host, fetch) = fetches.singleOrNull() ?: refuse("expected one fetch loading $REST_SOURCE and $REST_SOURCE_KEY, found ${fetches.size}")
    val fetchTakes = fetch.parameterTypes.map { it.toString() }
    if (!AccessFlags.PRIVATE.isSet(fetch.accessFlags) || fetch.returnType != "V" || fetchTakes.size != 4 || fetchTakes.drop(2) != listOf("Z", "Z")) {
        refuse("the fetch ${fetch.name} isn't the private (request, tab, Z, Z)V this was written for")
    }
    val tabType = fetchTakes[1]

    val entries = host.methods.filter { method ->
        val takes = method.parameterTypes.map { it.toString() }
        method.returnType == "V" && !AccessFlags.STATIC.isSet(method.accessFlags) && takes.size == 5 &&
            takes[0] == tabType && takes[1].startsWith("L") && takes.drop(2) == listOf("I", "Z", "Z")
    }
    val entry = entries.singleOrNull() ?: refuse("expected one load of $tabType, a user, a count and two flags in ${host.type}, found ${entries.size}")
    if (0 in entry.jumpTargets()) refuse("a jump enters the load at its first instruction")
    val implementation = entry.implementation ?: refuse("the load has no body")
    if (implementation.registerCount - entry.parameterTypes.size - 1 < ENTRY_LOCALS) refuse("the load has fewer than $ENTRY_LOCALS locals for the hook")

    val code = implementation.instructions.toList()
    val calls = code.indices.filter { at ->
        val call = code[at].methodCalled() ?: return@filter false
        (code[at].opcode == Opcode.INVOKE_STATIC || code[at].opcode == Opcode.INVOKE_STATIC_RANGE) &&
            call.parameterTypes.map { it.toString() } == listOf(USER_SESSION, STRING, STRING) && call.returnType.startsWith("L") &&
            code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT && code.getOrNull(at + 2)?.methodCalled()?.isSameMemberAs(fetch) == true
    }
    val at = calls.singleOrNull() ?: refuse("expected one request handed straight to the fetch in the load, found ${calls.size}")
    val called = code[at].methodCalled()!!
    val request = classDefByOrNull(called.definingClass)?.methods?.singleOrNull { it.isSameAs(called) }
        ?: refuse("this Instagram build has no ${called.definingClass}->${called.name}")
    if (!loadsString(request, TAGGED_POSTS_PATH)) refuse("the request ${request.name} doesn't load $TAGGED_POSTS_PATH")

    val before = code.subList(maxOf(0, at - ROUTE_WINDOW), at)
    val reads = before.filter { it.opcode == Opcode.IGET_OBJECT }.mapNotNull { (it as ReferenceInstruction).reference as? FieldReference }
    val states = before.mapNotNull { it.methodCalled() }.filter { it.parameterTypes.map { type -> type.toString() } == listOf(host.type, tabType) && it.returnType.startsWith("L") }
    val state = states.singleOrNull() ?: refuse("expected one call for the tab's paging state before the request, found ${states.size}")
    val pagings = reads.filter { it.definingClass == state.returnType }
    val paging = pagings.singleOrNull() ?: refuse("expected one read of the paging state before the request, found ${pagings.size}")
    val cursors = reads.filter { it.definingClass == paging.type && it.type == STRING }
    val cursor = cursors.singleOrNull() ?: refuse("expected one read of the cursor before the request, found ${cursors.size}")
    val users = reads.filter { it.type == STRING && it.definingClass != paging.type }
    val userId = users.singleOrNull() ?: refuse("expected one read of the user's id before the request, found ${users.size}")
    val sessions = reads.filter { it.definingClass == host.type && it.type == USER_SESSION }
    val session = sessions.singleOrNull() ?: refuse("expected one read of the session before the request, found ${sessions.size}")
    val pagingClass = classDefByOrNull(paging.type) ?: refuse("this Instagram build has no ${paging.type}")
    val frees = pagingClass.methods.filter { method ->
        method.returnType == "Z" && method.parameterTypes.map { it.toString() } == listOf("Z") &&
            !AccessFlags.STATIC.isSet(method.accessFlags) && !AccessFlags.PRIVATE.isSet(method.accessFlags)
    }
    val free = frees.singleOrNull() ?: refuse("expected one (Z)Z on the paging state, found ${frees.size}")

    val path = request.implementation?.instructions?.toList() ?: refuse("the request has no body")
    val loads = path.indices.filter { stringLoadedAt(path, it) == TAGGED_POSTS_PATH }
    val load = loads.singleOrNull() ?: refuse("expected the request to load $TAGGED_POSTS_PATH once, found ${loads.size}")
    // A const-string holds the path itself, a pool's call hands it to the move-result after it.
    val heldAt = if (path[load].opcode == Opcode.INVOKE_STATIC) load + 1 else load
    val pathRegister = (path.getOrNull(heldAt) as? OneRegisterInstruction)?.registerA ?: refuse("the request's path isn't held in a register")
    if (heldAt + 1 in request.jumpTargets()) refuse("something in the request jumps to just past its path")

    return GridJumpRest(entry, fetch, request, heldAt + 1, pathRegister, userId, state, paging, cursor, free, session)
}

/**
 * Only called once [findGridJump] found everything. The load asks the extension first thing, with its
 * locals free at its entry, whether this page takes the jump's route. An answer means it builds the
 * request with that max_id and hands it to the fetch as the tagged posts branch does, and the request
 * turns its path into the user's feed where it loads it. No answer, or a kind of user the branch can't
 * read, and the load goes on as Instagram wrote it. The tab and the bar's right row go to the extension
 * behind the hooks of Profile posts as a list and Profile extras, which keep the first spot in those
 * methods. The reload's stub is written from the arguments the extension passes as spare.
 */
internal fun BytecodePatchContext.applyGridJump(site: GridJumpSite) {
    val rest = site.rest
    mutableClassDefBy(rest.entry.definingClass).methods.single { it.isSameAs(rest.entry) }.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v0, p1
            move-object/from16 v1, p2
            move-object/from16 v4, p0
            instance-of v2, v1, ${rest.userId.definingClass}
            if-eqz v2, :stock
            check-cast v1, ${rest.userId.definingClass}
            iget-object v1, v1, ${rest.userId.spelled()}
            invoke-static { v4, v0 }, ${rest.state.spelled()}
            move-result-object v2
            iget-object v3, v2, ${rest.paging.spelled()}
            iget-object v5, v3, ${rest.cursor.spelled()}
            const/4 v6, 0x0
            invoke-virtual { v3, v6 }, ${rest.free.spelled()}
            move-result v6
            move/from16 v7, p4
            invoke-static { v2, v1, v7, v5, v6 }, $JUMP_REST_MAX_ID
            move-result-object v3
            if-eqz v3, :stock
            iget-object v2, v4, ${rest.session.spelled()}
            invoke-static { v2, v1, v3 }, ${rest.request.spelled()}
            move-result-object v2
            move/from16 v8, p5
            invoke-direct { v4, v2, v0, v7, v8 }, ${rest.fetch.spelled()}
            return-void
            :stock
            nop
        """,
    )

    mutableClassDefBy(rest.request.definingClass).methods.single { it.isSameAs(rest.request) }.addInstructions(
        rest.pathAt,
        """
            invoke-static/range { v${rest.pathRegister} .. v${rest.pathRegister} }, $JUMP_REST_PATH
            move-result-object v${rest.pathRegister}
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
private fun FieldReference.spelled(): String = "$definingClass->$name:$type"
private fun MethodReference.spelled(): String = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
private fun Method.isSameAs(other: MethodReference): Boolean =
    name == other.name && returnType == other.returnType && parameterTypes.map { it.toString() } == other.parameterTypes.map { it.toString() }
private fun MethodReference.isSameMemberAs(other: Method): Boolean = definingClass == other.definingClass && other.isSameAs(this)
private fun Instruction.methodCalled(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

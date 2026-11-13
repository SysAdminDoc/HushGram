/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.following

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.reels.profiletabbar.TabHost
import app.morphe.patches.instagram.reels.profiletabbar.findTabHosts
import app.morphe.patches.instagram.reels.profiletabbar.findTabRootCheck
import app.morphe.patches.instagram.reels.profiletabbar.requirePublic
import app.morphe.patches.instagram.reels.profiletabbar.writeFragmentList
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.returnEarlyClass
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val FAVORITES_PART = "Favorites from the tab bar"
private const val FAVORITES_CLASS = "$EXTENSION_PACKAGE/feed/FavoritesFeed;"
internal const val FAVORITES_SWITCH_READY = "$FAVORITES_CLASS->switchReady(Ljava/lang/Object;)V"
internal const val FAVORITES_TAB_ROOT = "$FAVORITES_CLASS->keepTabRoot(ILjava/lang/Object;)I"
internal const val FAVORITES_TARGETS = "$EXTENSION_PACKAGE/feed/FavoritesFeedTargets;"
private const val FRAGMENT_ACTIVITY = "Landroidx/fragment/app/FragmentActivity;"

private fun refuseFavorites(detail: String): Nothing = throw PatchException("$FAVORITES_PART: $detail")

/**
 * The one of Home's [switches] built for an activity: the listener the picker hands a picked feed
 * to, LX/00OV on 454. It opens any feed but For you and Following as a page on the current tab.
 */
internal fun BytecodePatchContext.findFavoritesSwitch(switches: List<FeedSwitch>): FeedSwitch {
    val built = switches.filter { switch -> classDefByOrNull(switch.type)?.methods?.any { it.buildsFor() } == true }
    return built.singleOrNull() ?: refuseFavorites("expected one feed switch built for an activity, found ${built.size}")
}

/**
 * Hands the extension Home's feed switch as it's built, opens Favorites through it, and keeps the
 * tab bar while Favorites is on top: each tab host asking whether its tab is at its root gets the
 * extension's answer too, as the reel profile's patch has it. Everything is found before anything
 * changes, and a build where any of it isn't as expected keeps the other switches. Answers whether
 * it's in.
 */
internal fun BytecodePatchContext.favoritesOrWarn(switches: List<FeedSwitch>): Boolean = try {
    val switch = findFavoritesSwitch(switches)
    requireFavoritesReachable(switch)
    val check = findTabRootCheck()
    val hosts = findTabHosts(check.method)
    val manager = hosts.map { it.manager }.distinctBy { it.toString() }.singleOrNull()
        ?: refuseFavorites("the tab hosts get their fragment manager differently")
    requirePublic(listOf(manager, check.fragments))
    check.store?.let { requirePublic(it) }
    val built = favoritesSwitchReturns(switch)
    val roots = hosts.map { favoritesRootAt(it, check.method) }
    built.forEach { (method, returns) ->
        returns.sortedDescending().forEach { at ->
            method.addInstructionsAtControlFlowLabel(at, "invoke-static/range { p0 .. p0 }, $FAVORITES_SWITCH_READY")
        }
    }
    roots.forEach { root ->
        root.method.addInstructions(
            root.at,
            """
                invoke-static { v${root.answer}, v${root.host} }, $FAVORITES_TAB_ROOT
                move-result v${root.answer}
            """.trimIndent(),
        )
    }
    writeFavoritesTargets(switch)
    writeFragmentList(manager, check, FAVORITES_TARGETS)
    true
} catch (refused: PatchException) {
    patchLog.warning("${refused.message}. $FAVORITES_PART isn't in this build, the other switches are.")
    false
}

/** The switch's class, its method and the feed type enum have to be public for the extension's stub to call. */
private fun BytecodePatchContext.requireFavoritesReachable(switch: FeedSwitch) {
    val owner = classDefByOrNull(switch.type) ?: refuseFavorites("${switch.type} isn't in the app")
    val method = owner.methods.single { it.name == switch.name && it.parameterTypes.map(CharSequence::toString) == listOf(switch.feedType) }
    val feedTypes = classDefByOrNull(switch.feedType) ?: refuseFavorites("${switch.feedType} isn't in the app")
    if (listOf(owner.accessFlags, method.accessFlags, feedTypes.accessFlags).any { !AccessFlags.PUBLIC.isSet(it) }) {
        refuseFavorites("${switch.type}->${switch.name} or ${switch.feedType} isn't public")
    }
}

/** Each constructor of the switch built for an activity, with its returns, where it still holds itself. */
private fun BytecodePatchContext.favoritesSwitchReturns(switch: FeedSwitch): List<Pair<MutableMethod, List<Int>>> =
    mutableClassDefBy(switch.type).methods.filter { it.buildsFor() }.map { method ->
        val code = method.implementation!!.instructions.toList()
        val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
        if (returns.isEmpty()) refuseFavorites("${switch.type}'s constructor never returns")
        method.requireThisIntact(FAVORITES_PART, returns)
        method to returns
    }

/** Where the hook goes in a tab host's [method]: at [at], with the root check's answer in [answer] and the host in [host]. */
private class FavoritesRoot(val method: MutableMethod, val at: Int, val answer: Int, val host: Int)

/**
 * A tab host's method as it is now, with where the hook goes in it: just past the read of the root
 * check. The reel profile's patch hooks the same read, so the index is found in the method as it
 * stands, either way round, and the hooks chain.
 */
private fun BytecodePatchContext.favoritesRootAt(host: TabHost, check: Method): FavoritesRoot {
    val parameters = host.method.parameterTypes.map(CharSequence::toString)
    val method = mutableClassDefBy(host.method.definingClass).methods.single {
        it.name == host.method.name && it.returnType == host.method.returnType &&
            it.parameterTypes.map(CharSequence::toString) == parameters
    }
    val code = method.implementation!!.instructions.toList()
    val call = code.indices.singleOrNull { at ->
        val called = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        called != null && called.definingClass == check.definingClass && called.name == check.name &&
            called.parameterTypes.map(CharSequence::toString) == check.parameterTypes.map(CharSequence::toString)
    } ?: refuseFavorites("${method.definingClass} doesn't ask the root check once")
    if (code.getOrNull(call + 1)?.opcode != Opcode.MOVE_RESULT) refuseFavorites("${method.definingClass} doesn't read the root check")
    if (call + 2 in method.jumpTargets()) refuseFavorites("something in ${method.definingClass} jumps past the root check")
    return FavoritesRoot(method, call + 2, host.answer, host.host)
}

/** Writes the stubs the extension reaches Home's feed types and feed switch through. */
private fun BytecodePatchContext.writeFavoritesTargets(switch: FeedSwitch) {
    val targets = mutableClassDefBy(FAVORITES_TARGETS)
    targets.methods.single { it.name == "feedTypes" }.returnEarlyClass(switch.feedType)
    targets.methods.single { it.name == "show" }.addInstructions(
        0,
        """
            check-cast p0, ${switch.type}
            check-cast p1, ${switch.feedType}
            invoke-virtual { p0, p1 }, ${switch.type}->${switch.name}(${switch.feedType})V
            return-void
        """.trimIndent(),
    )
}

private fun Method.buildsFor() = name == "<init>" && parameterTypes.firstOrNull()?.toString() == FRAGMENT_ACTIVITY

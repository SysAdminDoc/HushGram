/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.linktabbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.feed.isToString
import app.morphe.patches.instagram.feed.printedFlag
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.reels.profiletabbar.findTabHosts
import app.morphe.patches.instagram.reels.profiletabbar.findTabRootCheck
import app.morphe.patches.instagram.reels.profiletabbar.keepProfileAsTabRoot
import app.morphe.patches.instagram.reels.profiletabbar.requirePublic
import app.morphe.patches.instagram.reels.profiletabbar.writeFragmentList
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val PATCH = "Keep the tab bar on reel links"
internal const val REEL_LINK_STATUS = "reelLinkTabBar"
private const val ANY = "Ljava/lang/Object;"
internal const val REEL_LINK_HOOK = "$EXTENSION_PACKAGE/reels/ReelLinkTabBar;->open($ANY$ANY$ANY)Z"
internal const val REEL_LINK_TARGETS = "$EXTENSION_PACKAGE/reels/ReelLinkTabBarTargets;"
internal const val LINK_TAB_ROOT_HOOK = "$EXTENSION_PACKAGE/reels/ReelLinkTabBar;->keepTabRoot(I$ANY)I"

/**
 * The reel link route is the one method of the class that logs [LINK_HANDLER] reading [BACK_STACK]
 * and taking [LINK_VIEWER_PARAMETERS]: it builds the Reels viewer from the link's arguments and puts
 * it in the activity it's handed (0XcO.A02 on 456's 386214574). Its caller loads the log name.
 */
internal const val LINK_HANDLER = "clips_deeplink_handler"
internal const val BACK_STACK = "InstagramUrlLaunchConstants.EXTRA_SHOULD_INCLUDE_BACK_STACK"
internal const val BUNDLE = "Landroid/os/Bundle;"
internal const val FRAGMENT_ACTIVITY = "Landroidx/fragment/app/FragmentActivity;"
internal const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
internal val LINK_VIEWER_PARAMETERS = listOf(BUNDLE, FRAGMENT_ACTIVITY, USER_SESSION)

/** The Reels viewer's config, and the label its toString prints the flag it hides the tab bar by under. */
internal const val VIEWER_CONFIG = "Lcom/instagram/clips/intf/ClipsViewerConfig;"
internal const val TAB_BAR_HIDDEN = ", shouldHideTabBar="

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The viewer config's flag that hides the tab bar: a link's config sets it, since the link's own
 * screen has no tab bar, so the extension clears it on the config the reel takes to the tab. The
 * field is final and renamed, so the extension clears it by reflection, by this field's name.
 */
internal fun BytecodePatchContext.findTabBarFlag(): FieldReference {
    val config = classDefByOrNull(VIEWER_CONFIG) ?: refuse("no $VIEWER_CONFIG")
    val toString = config.methods.singleOrNull { it.isToString() } ?: refuse("$VIEWER_CONFIG has no toString")
    return toString.printedFlag(PATCH, VIEWER_CONFIG, TAB_BAR_HIDDEN)
}

/** Writes [flag]'s name into the stub the extension asks for it. */
internal fun BytecodePatchContext.writeTabBarFlag(flag: FieldReference) {
    mutableClassDefBy(REEL_LINK_TARGETS).methods.single { it.name == "tabBarHiddenFlag" }.returnEarly(flag.name)
}

internal fun BytecodePatchContext.findLinkViewerRoute(): Method {
    val found = classesLoadingString(LINK_HANDLER).flatMap { it.methods }.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map(CharSequence::toString) == LINK_VIEWER_PARAMETERS &&
            loadsString(method, BACK_STACK)
    }
    val route = found.singleOrNull() ?: refuse("expected one reel link route reading $BACK_STACK, found ${found.size}")
    val registers = route.implementation?.registerCount ?: refuse("the reel link route has no code")
    if (registers - LINK_VIEWER_PARAMETERS.size < 1) refuse("the reel link route has no register to spare")
    if (registers > 16) refuse("the reel link route's arguments are out of the hook's reach")
    if (0 in route.jumpTargets()) refuse("something in the reel link route jumps to its start")
    return route
}

/** At the route's start, asks the extension first and returns when the reel went onto the tab. */
internal fun BytecodePatchContext.askBeforeTheLinkViewer(route: Method) {
    val method = mutableClassDefBy(route.definingClass).methods.first {
        it.name == route.name && it.returnType == route.returnType &&
            it.parameterTypes.map(CharSequence::toString) == LINK_VIEWER_PARAMETERS
    }
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static { p0, p1, p2 }, $REEL_LINK_HOOK
            move-result v0
            if-eqz v0, :stock
            return-void
        """.trimIndent(),
        ExternalLabel("stock", method.getInstruction(0)),
    )
}

/** Writes the stub the extension opens the reel through, on the tab screen, with Instagram's own route. */
internal fun BytecodePatchContext.writeLinkViewerCall(route: Method) {
    val stub = mutableClassDefBy(REEL_LINK_TARGETS).methods.single { it.name == "show" }
    stub.addInstructions(
        0,
        """
            check-cast p0, $BUNDLE
            check-cast p1, $FRAGMENT_ACTIVITY
            check-cast p2, $USER_SESSION
            invoke-static { p0, p1, p2 }, ${route.definingClass}->${route.name}(${LINK_VIEWER_PARAMETERS.joinToString("")})V
            return-void
        """.trimIndent(),
    )
}

/**
 * Keeps the tab bar on a reel opened from a link (#385). Instagram opens a reel link in a screen of
 * its own, with no tab bar, so the extension closes that screen and puts the reel on the tab screen
 * under it, through the same route. On accounts where the tab hosts hide the bar for anything pushed
 * on a tab, the link's reel on top also counts as the tab's root, as a reel's profile does. See
 * ReelLinkTabBar in the extension. In the default selection with its switch off.
 */
@Suppress("unused")
val reelLinkTabBarPatch = bytecodePatch(
    name = "Keep the tab bar on reel links",
    description = "A reel link you open from another app plays on the tab you were on, with the tab bar under it. " +
        "Back goes back to that tab. Starts off. Turn it on in HushGram settings > Reels.",
) {
    category("Interaction")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(REEL_LINK_STATUS)
        val route = findLinkViewerRoute()
        requirePublic(listOf(route))
        val flag = findTabBarFlag()
        val check = findTabRootCheck()
        val hosts = findTabHosts(check.method)
        val manager = hosts.map { it.manager }.distinctBy { it.toString() }.singleOrNull() ?: refuse("the tab hosts get their fragment manager differently")
        requirePublic(listOf(manager, check.fragments))
        check.store?.let { requirePublic(it) }
        askBeforeTheLinkViewer(route)
        writeLinkViewerCall(route)
        writeTabBarFlag(flag)
        hosts.forEach { keepProfileAsTabRoot(it, LINK_TAB_ROOT_HOOK) }
        writeFragmentList(manager, check, REEL_LINK_TARGETS)
        enableStatus(REEL_LINK_STATUS)
    }
}

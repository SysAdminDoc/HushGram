/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.profile

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.profile.threads.USER_SESSION
import app.morphe.patches.instagram.stories.seen.hashedIdGetters
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags

/** The Bloks screen Instagram's About this account opens, which names the one method that opens it. */
internal const val ABOUT_SCREEN = "com.bloks.www.ig.about_this_account"

private const val ABOUT_CONTEXT = "Landroid/content/Context;"
private const val ABOUT_STRING = "Ljava/lang/String;"
private const val ABOUT_PATCH = "$PROFILE_PICTURE_PATCH: the About this account button"

/** Instagram's static call that opens About this account: [owner]'s [name], and the getter on the account that answers its id. */
internal class AboutAccountSite(val owner: String, val name: String, val idGetter: String)

/**
 * Finds what the About this account button needs, or null, after the patch log says why, so the
 * rest of Save profile picture goes in without it. The call is the one public static method taking
 * a context, the session and two strings that loads [ABOUT_SCREEN], and the account's id is the
 * one string getter its hashCode() asks, or whose code it runs on a 454 build that inlined it, the
 * way Show if a profile follows you finds it.
 */
internal fun BytecodePatchContext.findAboutAccount(): AboutAccountSite? {
    fun skip(why: String): AboutAccountSite? {
        patchLog.warning("$ABOUT_PATCH: $why. The patch goes in without it.")
        return null
    }
    val calls = classesLoadingString(ABOUT_SCREEN).flatMap { host ->
        host.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && AccessFlags.PUBLIC.isSet(method.accessFlags) && method.returnType == "V" &&
                method.parameterTypes.map { it.toString() } == listOf(ABOUT_CONTEXT, USER_SESSION, ABOUT_STRING, ABOUT_STRING) &&
                loadsString(method, ABOUT_SCREEN)
        }.map { host to it }
    }
    val (host, call) = calls.singleOrNull() ?: return skip("expected one call opening $ABOUT_SCREEN, found ${calls.size}")
    if (!AccessFlags.PUBLIC.isSet(host.accessFlags)) return skip("${host.type} isn't public, so the extension can't reach it")
    val session = classDefByOrNull(USER_SESSION) ?: return skip("$USER_SESSION isn't in this build")
    val user = classDefByOrNull(USER) ?: return skip("$USER isn't in this build")
    if (!AccessFlags.PUBLIC.isSet(session.accessFlags) || !AccessFlags.PUBLIC.isSet(user.accessFlags)) {
        return skip("$USER_SESSION or $USER isn't public")
    }
    val hash = user.methods.singleOrNull {
        it.name == "hashCode" && it.parameterTypes.isEmpty() && it.returnType == "I" && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: return skip("$USER has no hashCode()")
    val getters = hashedIdGetters(user.methods, hash)
    val id = getters.singleOrNull() ?: return skip("expected $USER's hashCode() to ask one getter of its ID, found $getters")
    val getter = user.methods.singleOrNull { it.name == id && it.parameterTypes.isEmpty() && it.returnType == ABOUT_STRING }
        ?: return skip("$USER has no $id()")
    if (!AccessFlags.PUBLIC.isSet(getter.accessFlags)) return skip("$USER->$id isn't public")
    return AboutAccountSite(host.type, call.name, id)
}

/**
 * Only called once [findAboutAccount] found everything: the extension's three stubs say the build
 * has the call, read an account's id and open the screen through Instagram's own call, with the
 * referer the extension hands it (a null one would make it its feed-ads entry).
 */
internal fun BytecodePatchContext.applyAboutAccount(site: AboutAccountSite) {
    val methods = mutableClassDefBy(PROFILE_EXTRAS).methods
    methods.single { it.name == "aboutAvailable" }.returnEarly(true)
    methods.single { it.name == "userId" }.addInstructions(
        0,
        """
            check-cast p0, $USER
            invoke-virtual { p0 }, $USER->${site.idGetter}()$ABOUT_STRING
            move-result-object p0
            return-object p0
        """,
    )
    methods.single { it.name == "openAbout" }.addInstructions(
        0,
        """
            check-cast p1, $USER_SESSION
            invoke-static { p0, p1, p2, p3 }, ${site.owner}->${site.name}($ABOUT_CONTEXT$USER_SESSION$ABOUT_STRING$ABOUT_STRING)V
            return-void
        """,
    )
}

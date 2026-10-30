/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.tabs

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Hide profile tabs"
internal const val PROFILE_TABS = "$EXTENSION_PACKAGE/profile/ProfileTabs;"
internal const val TABS_HOOK = "$PROFILE_TABS->tabs(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/List;)Ljava/util/List;"

private const val LIST = "Ljava/util/List;"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val USER = "Lcom/instagram/user/model/User;"

/** The signed-in account, whose `getUserId()` keeps its name, so your own profile is told apart. */
private const val SIGNED_IN = "Lcom/instagram/common/session/UserSession;"
private const val SIGNED_IN_ID = "getUserId"

/** The keys Instagram gives the profile's tabs, which the builder of a profile's tab list names. */
internal val PROFILE_TAB_KEYS = listOf(
    "profile_media_grid", "profile_clips", "profile_reposts", "profile_tagged_media_photos_of_you",
)

/**
 * Leaves the Reels, Reposts and Tagged tabs off profiles as the user picks, once for your own
 * profile and once for everyone else's (#296). Included in the default selection with nothing
 * picked, so every tab stays until the user turns one off.
 *
 * Instagram builds the list of a profile's tab keys in one method, and the strip, the pager and the
 * tab providers are made from that list. The patch hands the list to the extension just before the
 * method returns it, with the builder (which keeps the signed-in session) and the profile's user,
 * so a tab is dropped by its key and not by where it sits, and the extension can tell your own
 * profile by comparing the two accounts' IDs. The grid is never offered. With a single tab left
 * Instagram draws no strip, as it does for an account that only has a grid.
 */
@Suppress("unused")
val hideProfileTabsPatch = bytecodePatch(
    name = "Hide profile tabs",
    description = "Lets you take the Reels, Reposts and Tagged tabs off profiles, yours and other people's, and keep " +
        "the grid. Starts with every tab showing. Pick which to hide in HushGram settings > Profiles.",
    default = true,
) {
    category("Profiles")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("profileTabs")
        hideProfileTabs(findTabList())
        enableStatus("profileTabs")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Where the hook goes: the builder of a profile's tab keys and its one return, the registers
 * holding the builder, the user and the list there, the builder's field keeping the signed-in
 * session and the user's getter of its ID.
 */
internal class TabListSite(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returns: Int,
    val register: Int,
    val self: Int,
    val user: Int,
    val session: String,
    val userId: String,
)

/**
 * Finds the builder of a profile's tab keys: the one method taking a user and answering a list that
 * names all four of [PROFILE_TAB_KEYS]. It has to be the only one, and return in one place, with
 * nothing jumping onto that return, since a jump keeps its target on the original instruction and
 * would skip the hook. The builder and the user have to be in their own registers there, the
 * builder's class has to keep one signed-in session, and the user's ID has to come from the one
 * getter its hashCode() asks. Fails before anything changes when any of that isn't so, since that's
 * an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findTabList(): TabListSite {
    val found = classesLoadingString(PROFILE_TAB_KEYS.last()).flatMap { holder ->
        holder.methods.filter { method ->
            method.returnType == LIST && method.parameterTypes.map(CharSequence::toString) == listOf(USER) &&
                PROFILE_TAB_KEYS.all { key -> loadsString(method, key) }
        }.map { holder to it }
    }
    val (holder, builder) = found.singleOrNull()
        ?: refuse("expected one builder of a profile's tab keys, found ${found.size}")
    val where = "${builder.definingClass}->${builder.name}"
    val code = builder.implementation?.instructions?.toList().orEmpty()
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
    val at = returns.singleOrNull() ?: refuse("$where returns in ${returns.size} places")
    if (at in builder.jumpTargets()) refuse("$where has a jump onto its return")
    val register = (code[at] as OneRegisterInstruction).registerA

    // The hook reads the builder and the user where the list goes back.
    if (AccessFlags.STATIC.isSet(builder.accessFlags)) refuse("$where is static, so it keeps no signed-in session")
    builder.requireThisIntact(PATCH, listOf(at))
    builder.requireParameterIntact(PATCH, 0, listOf(at))
    val self = builder.localRegisterCount()
    val user = builder.parameterRegisterNumber(0)
    val wide = listOf(self, user, register).filter { it > 15 }
    if (wide.isNotEmpty()) refuse("$where keeps the hook's ${wide.joinToString { "v$it" }} past v15, which its call can't name")

    // The extension reads the session off the builder, so the class and the field are public.
    val sessions = holder.fields.filter { it.type == SIGNED_IN && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val session = sessions.singleOrNull()
        ?: refuse("expected ${holder.type} to keep one $SIGNED_IN, found ${sessions.size}")
    if (!AccessFlags.PUBLIC.isSet(holder.accessFlags) || !AccessFlags.PUBLIC.isSet(session.accessFlags)) {
        refuse("${holder.type}->${session.name} isn't public, so the extension can't read the session")
    }
    val signedIn = classDefByOrNull(SIGNED_IN) ?: refuse("$SIGNED_IN isn't in this build")
    signedIn.methods.singleOrNull {
        it.name == SIGNED_IN_ID && it.parameterTypes.isEmpty() && it.returnType == STRING &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("$SIGNED_IN has no public $SIGNED_IN_ID()")
    if (!AccessFlags.PUBLIC.isSet(signedIn.accessFlags)) refuse("$SIGNED_IN isn't public, so the extension can't reach it")

    // The user model hashes its ID, so hashCode() asks its one getter of it.
    val userClass = classDefByOrNull(USER) ?: refuse("$USER isn't in this build")
    val hashCode = userClass.methods.singleOrNull {
        it.name == "hashCode" && it.parameterTypes.isEmpty() && it.returnType == "I" && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("$USER has no hashCode()")
    val idGetters = hashCode.implementation?.instructions?.toList().orEmpty().mapNotNull {
        (it as? ReferenceInstruction)?.reference as? MethodReference
    }.filter { it.definingClass == USER && it.parameterTypes.isEmpty() && it.returnType == STRING }.map { it.name }.distinct()
    val idGetter = idGetters.singleOrNull()
        ?: refuse("expected $USER's hashCode() to ask one getter of its ID, found $idGetters")
    val getter = userClass.methods.singleOrNull { it.name == idGetter && it.parameterTypes.isEmpty() && it.returnType == STRING }
        ?: refuse("$USER has no $idGetter()")
    if (!AccessFlags.PUBLIC.isSet(userClass.accessFlags) || !AccessFlags.PUBLIC.isSet(getter.accessFlags)) {
        refuse("$USER->$idGetter isn't public, so the extension can't read the ID")
    }

    return TabListSite(
        builder.definingClass, builder.name, builder.parameterTypes.map(CharSequence::toString), at, register,
        self, user, "${holder.type}->${session.name}:$SIGNED_IN", "$USER->$idGetter()$STRING",
    )
}

/**
 * Hands the builder, the user and the list to [TABS_HOOK] right before the builder returns the
 * list, and returns what comes back. Writes the two readers the hook tells your own profile with:
 * the profile's ID and the signed-in account's.
 */
internal fun BytecodePatchContext.hideProfileTabs(site: TabListSite) {
    val extension = mutableClassDefBy(PROFILE_TABS)
    fun stub(name: String): MutableMethod = extension.methods.singleOrNull {
        it.name == name && it.returnType == STRING && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(CharSequence::toString) == listOf(OBJECT)
    } ?: refuse("$PROFILE_TABS has no static $STRING $name($OBJECT)")
    val profileId = stub("profileId")
    val viewerId = stub("viewerId")

    val builder = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    val v = site.register
    builder.addInstructions(
        site.returns,
        """
            invoke-static { v${site.self}, v${site.user}, v$v }, $TABS_HOOK
            move-result-object v$v
        """,
    )
    profileId.addInstructions(
        0,
        """
            check-cast p0, $USER
            invoke-virtual { p0 }, ${site.userId}
            move-result-object p0
            return-object p0
        """,
    )
    viewerId.addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${site.type}
            iget-object p0, p0, ${site.session}
            if-nez p0, :signed_in
            const/4 p0, 0x0
            return-object p0
            :signed_in
            invoke-virtual { p0 }, $SIGNED_IN->$SIGNED_IN_ID()$STRING
            move-result-object p0
            return-object p0
        """,
    )
}

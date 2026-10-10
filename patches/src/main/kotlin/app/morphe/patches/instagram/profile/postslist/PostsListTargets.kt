/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.postslist

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val POSTS_LIST = "$EXTENSION_PACKAGE/profile/PostsList;"
internal const val RESUMED = "$POSTS_LIST->resumed(Ljava/lang/Object;)V"

/** A profile's tab of posts, one for each grid (posts, tagged and the rest), which keeps its name in 450. */
internal const val PROFILE_MEDIA_TAB = "Lcom/instagram/profile/fragment/ProfileMediaTabFragment;"
/** The class that sets up a profile's tabs, which keeps its name too and names the posts tab. */
internal const val PROFILE_TAB_CONTROLLER = "Lcom/instagram/profile/fragment/UserDetailTabController;"
internal const val FRAGMENT = "Landroidx/fragment/app/Fragment;"
internal const val BUNDLE = "Landroid/os/Bundle;"
/** The view each post of a grid is drawn in. */
internal const val GRID_CELL = "Lcom/instagram/igds/components/imagebutton/IgMultiImageButton;"

/** The tab's grid, which the extension reads by this name. */
internal const val GRID_FIELD = "recyclerView"
internal const val RECYCLER_VIEW = "Landroidx/recyclerview/widget/RecyclerView;"

/** The keys of the tab's arguments the extension reads, and what the posts tab is called. */
internal const val TAB_KEY = "ProfileMediaTabFragment.profile_tab_identifier"
internal const val SELF_KEY = "ProfileMediaTabFragment.is_self_profile"
internal const val POSTS_TAB = "profile_media_grid"

/** The tab's onResume, the framework's own, so its name and shape are kept. */
internal object PostsTabResumeFingerprint : Fingerprint(
    definingClass = PROFILE_MEDIA_TAB,
    name = "onResume",
    returnType = "V",
    parameters = listOf(),
)

private fun refuse(why: String): Nothing = throw PatchException("$POSTS_LIST_PATCH: $why")

/**
 * Resolve the posts tab's onResume and prove what the extension relies on, before any edit: nothing
 * jumps to its first instruction; the tab reads its arguments by the keys the extension reads; it
 * keeps its grid in a field of that name; it's a Fragment whose getArguments and isResumed the
 * extension can call; Instagram still calls the posts tab by the name the extension looks for; the
 * grid's cells are the class the extension looks for; and the extension has its method.
 */
internal fun BytecodePatchContext.findPostsTabResume(): MutableMethod {
    val resume = uniqueMethod(POSTS_LIST_PATCH, "posts tab onResume", PostsTabResumeFingerprint)
    if (AccessFlags.STATIC.isSet(resume.accessFlags)) refuse("the posts tab's onResume is static")
    if (resume.implementation?.instructions?.firstOrNull() == null) refuse("the posts tab's onResume has no body")
    if (0 in resume.jumpTargets()) refuse("a jump or exception handler enters the posts tab's onResume at its first instruction")

    val tab = classDefByOrNull(PROFILE_MEDIA_TAB) ?: refuse("no posts tab class")
    val onCreate = tab.methods.singleOrNull { it.name == "onCreate" && it.parameterTypes.map(Any::toString) == listOf(BUNDLE) }
        ?: refuse("the posts tab has no onCreate(Bundle)")
    for (key in listOf(TAB_KEY, SELF_KEY)) if (key !in onCreate.strings()) refuse("the posts tab's onCreate doesn't read $key")
    tab.fields.singleOrNull { it.name == GRID_FIELD && it.type == RECYCLER_VIEW }
        ?.takeIf { AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags) }
        ?: refuse("the posts tab has no public $GRID_FIELD field")

    val fragment = superclasses(tab).firstOrNull { it.type == FRAGMENT } ?: refuse("the posts tab isn't a $FRAGMENT")
    for ((name, returns) in listOf("getArguments" to BUNDLE, "isResumed" to "Z")) {
        fragment.methods.singleOrNull {
            it.name == name && it.returnType == returns && it.parameterTypes.isEmpty() &&
                AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: refuse("$FRAGMENT has no public $name()$returns")
    }

    val controller = classDefByOrNull(PROFILE_TAB_CONTROLLER) ?: refuse("no profile tab controller")
    if (controller.methods.none { POSTS_TAB in it.strings() }) refuse("the profile tab controller doesn't name the $POSTS_TAB tab")
    classDefByOrNull(GRID_CELL) ?: refuse("no grid cell class $GRID_CELL")

    classDefByOrNull(POSTS_LIST)?.methods?.singleOrNull {
        it.name == "resumed" && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;") &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("extension has no public static resumed(Ljava/lang/Object;)V")
    return resume
}

/** [tab]'s superclasses as far as this APK defines them, nearest first. */
private fun BytecodePatchContext.superclasses(tab: ClassDef): List<ClassDef> {
    val found = mutableListOf<ClassDef>()
    var next = tab.superclass
    while (next != null && found.size < 16) {
        val superclass = classDefByOrNull(next) ?: break
        found += superclass
        next = superclass.superclass
    }
    return found
}

private fun Method.strings(): Set<String> = implementation?.instructions
    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }?.toSet().orEmpty()

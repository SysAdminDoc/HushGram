/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.origin

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.download.activeTrialBridge
import app.morphe.patches.instagram.download.mediaTypeBridge
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.extension.typesMarked
import app.morphe.util.RegisterLiveness
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val REEL_ORIGIN = "$EXTENSION_PACKAGE/reels/ReelOrigin;"
internal const val OPENING = "$REEL_ORIGIN->opening(Ljava/lang/Object;)V"
internal const val TAB_RESUMED = "$REEL_ORIGIN->reelsTabResumed(Ljava/lang/Object;)V"
internal const val GRID_RESUMED = "$REEL_ORIGIN->postsGridResumed(Ljava/lang/Object;)V"

internal const val MEDIA = "Lcom/instagram/feed/media/Media;"
internal const val FRAGMENT = "Landroidx/fragment/app/Fragment;"
internal const val FRAGMENT_ACTIVITY = "Landroidx/fragment/app/FragmentActivity;"
internal const val BUNDLE = "Landroid/os/Bundle;"
internal const val RECYCLER_VIEW = "Landroidx/recyclerview/widget/RecyclerView;"
internal const val TRANSITION_NAME = "Landroid/view/View;->getTransitionName()Ljava/lang/String;"

/**
 * The purge markers of what the patch hooks and relies on: the call the Reels viewer's author row
 * makes to open a profile, and the Reels tab of a profile, its creation and its own lookup of a
 * grid tile by media id.
 */
internal const val OPEN_PROFILE = "AuthorInfoNavigator_navigateToNormalProfile"
internal const val TAB_RESUME = "ClipsProfileTabFragment_onResume"
internal const val TAB_CREATE = "ClipsProfileTabFragment_onCreate"
internal const val TILE_LOOKUP = "ClipsProfileTabFragment_findThumbnailViewForMediaId"

/** The Reels tab's argument the extension reads to know whose profile it is. */
internal const val USER_KEY = "ClipsProfileTabFragment.ARGS_PROFILE_USER_ID"

/**
 * A profile's posts grid, which keeps its name and holds no marker, and the argument the extension
 * reads to tell its main grid from its tagged one. A photo reel's tile sits there (#225).
 */
internal const val POSTS_GRID = "Lcom/instagram/profile/fragment/ProfileMediaTabFragment;"
internal const val GRID_KEY = "ProfileMediaTabFragment.profile_tab_identifier"

/**
 * What the patch writes into: the profile opener and the reel's media field it reads, the Reels
 * tab's onResume, for photo reels the posts grid's, and the step that writes the trial reel bridge,
 * each of the last two when it can be told.
 */
internal class ReelOriginTargets(
    val openProfile: Method,
    val media: Field,
    val tabResume: Method,
    val photoReels: PhotoReelTargets?,
    val writeTrial: (() -> Unit)?,
)

/** The posts grid's onResume, and the step that writes the media type bridge the extension reads. */
internal class PhotoReelTargets(val gridResume: Method, val writeMediaType: () -> Unit)

private fun refuse(why: String): Nothing = throw PatchException("$REEL_ORIGIN_PATCH: $why")

/**
 * Resolve both hook sites and prove what the extension relies on, before any edit. The profile
 * opener is the one method holding its marker, an instance method of one object, whose class has one
 * Media field it reads; nothing jumps to its first instruction, and v0 is a local it writes before
 * any read, so the hook can borrow it. Media keeps a public getId(). The Reels tab's onResume is
 * the one method holding its marker, the framework's own ()V, entered only at its first instruction,
 * in a Fragment whose getActivity, getArguments and isResumed are public. The same class creates the
 * tab reading the user id key, looks a tile up by its transition name, and keeps its grid in one
 * public RecyclerView field. The extension has both methods.
 */
internal fun BytecodePatchContext.findReelOriginTargets(): ReelOriginTargets {
    val held = marked(OPEN_PROFILE, TAB_RESUME, TAB_CREATE, TILE_LOOKUP)
    fun one(marker: String): Method {
        val found = held[marker].orEmpty()
        return found.singleOrNull() ?: refuse(
            "expected one method holding the $marker marker, found " +
                if (found.isEmpty()) "none" else found.joinToString { "${it.definingClass}->${it.name}" },
        )
    }

    val open = one(OPEN_PROFILE)
    val opener = "${open.definingClass}->${open.name}"
    if (AccessFlags.STATIC.isSet(open.accessFlags)) refuse("$opener is static")
    val destination = open.parameterTypes.singleOrNull()?.toString()?.takeIf { it.startsWith("L") }
        ?: refuse("$opener doesn't take one object")
    if (open.implementation?.instructions?.firstOrNull() == null) refuse("$opener has no body")
    if (0 in open.jumpTargets()) refuse("a jump or exception handler enters $opener at its first instruction")
    if (open.localRegisterCount() < 1) refuse("$opener has no local register for the hook")
    if (0 in RegisterLiveness.of(open).liveInto(0)) refuse("$opener reads v0 before writing it")
    val media = classDefByOrNull(destination)?.fields
        ?.filter { it.type == MEDIA && !AccessFlags.STATIC.isSet(it.accessFlags) }
        ?.singleOrNull() ?: refuse("$destination doesn't hold exactly one $MEDIA")
    if (open.implementation!!.instructions.none { instruction ->
            instruction.opcode == Opcode.IGET_OBJECT &&
                ((instruction as ReferenceInstruction).reference as FieldReference).let {
                    it.definingClass == destination && it.name == media.name && it.type == MEDIA
                }
        }) refuse("$opener doesn't read $destination->${media.name}")
    classDefByOrNull(MEDIA)?.methods?.singleOrNull {
        it.name == "getId" && it.returnType == "Ljava/lang/String;" && it.parameterTypes.isEmpty() &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("$MEDIA has no public getId()Ljava/lang/String;")

    val resume = one(TAB_RESUME)
    if (resume.name != "onResume" || resume.returnType != "V" || resume.parameterTypes.isNotEmpty() ||
        AccessFlags.STATIC.isSet(resume.accessFlags)
    ) refuse("the Reels tab's marker isn't in an instance onResume()V")
    if (resume.implementation?.instructions?.firstOrNull() == null) refuse("the Reels tab's onResume has no body")
    if (0 in resume.jumpTargets()) refuse("a jump or exception handler enters the Reels tab's onResume at its first instruction")
    val tab = classDefByOrNull(resume.definingClass) ?: refuse("no Reels tab class")
    if (USER_KEY !in one(TAB_CREATE).also { it.sameClass(tab, TAB_CREATE) }.strings()) {
        refuse("the Reels tab's onCreate doesn't read $USER_KEY")
    }
    if (one(TILE_LOOKUP).also { it.sameClass(tab, TILE_LOOKUP) }.calls().none { it == TRANSITION_NAME }) {
        refuse("the Reels tab doesn't look its tiles up by transition name")
    }
    val grids = tab.fields.filter { it.type == RECYCLER_VIEW && !AccessFlags.STATIC.isSet(it.accessFlags) }
    if (grids.size != 1) refuse("the Reels tab has ${grids.size} RecyclerView fields, not one")
    if (!AccessFlags.PUBLIC.isSet(grids.single().accessFlags)) refuse("the Reels tab's grid ${grids.single().name} isn't public")

    val fragment = superclasses(tab).firstOrNull { it.type == FRAGMENT } ?: refuse("the Reels tab isn't a $FRAGMENT")
    for ((name, returns) in listOf("getActivity" to FRAGMENT_ACTIVITY, "getArguments" to BUNDLE, "isResumed" to "Z")) {
        fragment.methods.singleOrNull {
            it.name == name && it.returnType == returns && it.parameterTypes.isEmpty() &&
                AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: refuse("$FRAGMENT has no public $name()$returns")
    }

    val extension = classDefByOrNull(REEL_ORIGIN)
    for (name in listOf("opening", "reelsTabResumed")) {
        extension?.methods?.singleOrNull {
            it.name == name && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;") &&
                AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: refuse("extension has no public static $name(Ljava/lang/Object;)V")
    }
    return ReelOriginTargets(open, media, resume, findPhotoReelTargets(), findTrialBridge())
}

/**
 * Media's one getter of `is_active_trial`, which the extension reads to skip a trial reel, one that
 * isn't on its poster's profile (#225). Only that skip needs it, so a build where it can't be told
 * answers null, after the patch log says why, and every reel is looked for.
 */
private fun BytecodePatchContext.findTrialBridge(): (() -> Unit)? = try {
    activeTrialBridge(REEL_ORIGIN_PATCH)
} catch (unknown: PatchException) {
    patchLog.warning("${unknown.message}. $REEL_ORIGIN_PATCH goes in looking for trial reels too.")
    null
}

/**
 * The posts grid's hook site and the media type bridge, proved the same way: the grid's own
 * onResume()V, still holding itself just past its one super.onResume(), in a Fragment that creates it
 * reading [GRID_KEY] and keeps its grid in one public RecyclerView field; the extension's
 * postsGridResumed; and Media's one getter of `media_type`. Photo reels alone need them, so a build
 * where any can't be told answers null, after the patch log says why, and the patch goes in
 * finding video reels alone.
 */
private fun BytecodePatchContext.findPhotoReelTargets(): PhotoReelTargets? = try {
    val grid = classDefByOrNull(POSTS_GRID) ?: refuse("no $POSTS_GRID")
    val resume = grid.methods.singleOrNull {
        it.name == "onResume" && it.returnType == "V" && it.parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("the posts grid has no instance onResume()V")
    resume.requireThisIntact(REEL_ORIGIN_PATCH, listOf(resume.afterSuperResume()))
    val create = grid.methods.singleOrNull { it.name == "onCreate" && it.parameterTypes.map(Any::toString) == listOf(BUNDLE) }
    if (create == null || GRID_KEY !in create.strings()) refuse("the posts grid's onCreate doesn't read $GRID_KEY")
    val grids = grid.fields.filter { it.type == RECYCLER_VIEW && !AccessFlags.STATIC.isSet(it.accessFlags) }
    if (grids.size != 1) refuse("the posts grid has ${grids.size} RecyclerView fields, not one")
    if (!AccessFlags.PUBLIC.isSet(grids.single().accessFlags)) refuse("the posts grid's ${grids.single().name} isn't public")
    if (superclasses(grid).none { it.type == FRAGMENT }) refuse("the posts grid isn't a $FRAGMENT")
    classDefByOrNull(REEL_ORIGIN)?.methods?.singleOrNull {
        it.name == "postsGridResumed" && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;") &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("extension has no public static postsGridResumed(Ljava/lang/Object;)V")
    PhotoReelTargets(resume, mediaTypeBridge(REEL_ORIGIN_PATCH))
} catch (unknown: PatchException) {
    patchLog.warning("${unknown.message}. $REEL_ORIGIN_PATCH goes in finding video reels alone.")
    null
}

/**
 * Where the posts grid's hook goes: just past the grid onResume's one super.onResume(), which nothing
 * jumps to. Not its first instruction, which Profile posts as a list takes. Read again on the method
 * being written, where that patch's call may already sit in front.
 */
internal fun Method.afterSuperResume(): Int {
    val code = implementation?.instructions?.toList() ?: refuse("the posts grid's onResume has no body")
    val supers = code.indices.filter { at ->
        val called = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
        code[at].opcode == Opcode.INVOKE_SUPER && called?.name == "onResume" && called.returnType == "V" &&
            called.parameterTypes.isEmpty()
    }
    val index = (supers.singleOrNull() ?: refuse("the posts grid's onResume doesn't call super.onResume once, found ${supers.size}")) + 1
    if (index !in code.indices) refuse("the posts grid's onResume ends on its super call")
    if (index in jumpTargets()) refuse("something in the posts grid's onResume jumps to just past its super call")
    return index
}

/** The methods holding each of [names] as a marker, read from the classes the index says hold one. */
private fun BytecodePatchContext.marked(vararg names: String): Map<String, List<Method>> {
    val wanted = names.toSet()
    val found = mutableMapOf<String, MutableList<Method>>()
    for (type in typesMarked(*names).sorted()) {
        val classDef = classDefByOrNull(type) ?: continue
        classDef.methods.forEach { method ->
            method.markers().filter { it in wanted }.distinct().forEach { found.getOrPut(it) { mutableListOf() } += method }
        }
    }
    return found
}

private fun Method.sameClass(tab: ClassDef, marker: String) {
    if (definingClass != tab.type) refuse("the $marker marker is in $definingClass, not the Reels tab ${tab.type}")
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

private fun Method.calls(): List<String> = implementation?.instructions
    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.toString() }.orEmpty()

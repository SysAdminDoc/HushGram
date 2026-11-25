/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.viewers

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.feed.confirm.publicStatic
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.patchLog
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction

internal const val STORY_VIEWER_SEARCH = "$EXTENSION_PACKAGE/settings/StoryViewerSearch;"

/** Hands the extension one row of a story's viewer list as Instagram binds it (#833). */
internal const val STORY_VIEWER_ROW = "$STORY_VIEWER_SEARCH->row(Landroid/view/View;)V"

/**
 * One row of a story's viewer list (`story_viewers_list_interaction_row`), the same id in every 457
 * build (aapt2; read it again each update).
 */
internal const val VIEWER_ROW_LAYOUT = 0x7f0e1618

private const val VIEW = "Landroid/view/View;"
private const val VIEW_GROUP = "Landroid/view/ViewGroup;"
private const val ANY = "Ljava/lang/Object;"

private fun Method.takes(vararg types: String) = parameterTypes.map(Any::toString) == types.toList()

/**
 * Where each row of a story's viewer list is bound: of the classes loading [VIEWER_ROW_LAYOUT], each
 * one with a method making a view from an int and a holder that loads it (Instagram's row maker,
 * `LX/0YXO;->createView` on 386314341), and that class's one instance method binding an int, a
 * view and two objects. Answers none, with a warning, when no class makes the row, so the list
 * keeps no search bar and Keep story viewers works as before. Refuses when a maker has no one bind
 * method or the extension lacks [STORY_VIEWER_ROW].
 */
internal fun BytecodePatchContext.findViewerRowBinders(): List<MutableMethod> {
    val makers = classesLoading(VIEWER_ROW_LAYOUT.toLong()).distinctBy { it.type }.filter { type ->
        type.methods.any { method ->
            method.returnType == VIEW && method.takes("I", VIEW_GROUP) && method.implementation?.instructions?.any {
                (it as? NarrowLiteralInstruction)?.narrowLiteral == VIEWER_ROW_LAYOUT
            } == true
        }
    }
    if (makers.isEmpty()) {
        patchLog.warning("$STORY_VIEWERS_PATCH: nothing makes a viewer list's rows, so the list gets no Search viewers bar")
        return emptyList()
    }
    val hook = classDefByOrNull(STORY_VIEWER_SEARCH)?.publicStatic(STORY_VIEWER_ROW) != null
    if (!hook) throw PatchException("$STORY_VIEWERS_PATCH: the extension has no public static $STORY_VIEWER_ROW")
    return makers.map { maker ->
        val binds = maker.methods.filter {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.takes("I", VIEW, ANY, ANY) &&
                it.implementation != null
        }
        val bind = binds.singleOrNull()
            ?: throw PatchException("$STORY_VIEWERS_PATCH: expected one bind method on ${maker.type}, found ${binds.size}")
        mutableClassDefBy(maker.type).methods.single {
            it.name == bind.name && it.returnType == "V" && it.takes("I", VIEW, ANY, ANY)
        }
    }
}

/**
 * Hands each row of a story's viewer list to the extension first thing in its bind, which puts a
 * Search viewers bar on the row's list once, with its switch on (#833). Instagram's own search there
 * now asks for a subscription. The hook reads the bind's own view parameter before anything can
 * reuse its register, and borrows nothing. A bind something jumps back to the start of keeps no hook,
 * with a warning, since the jump would run it again.
 */
internal fun BytecodePatchContext.searchViewerLists() {
    for (bind in findViewerRowBinders()) {
        if (0 in bind.jumpTargets()) {
            patchLog.warning("$STORY_VIEWERS_PATCH: ${bind.definingClass}->${bind.name} jumps back to its start, so its list gets no Search viewers bar")
            continue
        }
        bind.addInstructions(0, "invoke-static/range {p2 .. p2}, $STORY_VIEWER_ROW")
    }
}

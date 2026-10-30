/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.ailabel

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.reels.partnership.ReelsPageSites
import app.morphe.patches.instagram.reels.partnership.fillReelsItemMedia
import app.morphe.patches.instagram.reels.partnership.fillStub
import app.morphe.patches.instagram.reels.partnership.filterReelsPage
import app.morphe.patches.instagram.reels.partnership.findReelsPage
import app.morphe.patches.instagram.reels.partnership.isStaticStub
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction

private const val PATCH = "Hide AI-labeled reels"
internal const val AI_LABELED_REELS = "$EXTENSION_PACKAGE/reels/AiLabeledReels;"
internal const val AI_PAGE_FILTER = "$AI_LABELED_REELS->page(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;"

/** The Media field holding a post's AI label, which Instagram's Reels subtitle and label type read. */
internal const val AI_LABEL_FIELD = "ai_label_info"

/** The label's flag saying to show its attribution label. Instagram shows the label only when it's TRUE. */
internal const val READS_LABEL_FIELD = "should_read_attribution_label"

/** The label's text. Instagram shows the label only when it isn't empty. */
internal const val LABEL_TEXT_FIELD = "attribution_label_text"

/** The type name Instagram's tree-backed AI label class answers. */
internal const val AI_LABEL_TYPE_NAME = "XDTAILabelInfo"

private const val OBJECT = "Ljava/lang/Object;"
private const val BOOLEAN = "Ljava/lang/Boolean;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"

/**
 * Leaves reels that carry Instagram's own AI label out of the Reels tab. Reels the user went to see
 * stay. In the default selection with its switch off: the label is Instagram's, and leaving those
 * reels out is the user's pick. Asked for in #174.
 */
@Suppress("unused")
val hideAiLabeledReelsPatch = bytecodePatch(
    name = "Hide AI-labeled reels",
    description = "Leaves out reels that carry Instagram's own AI label. A reel without that label stays. " +
        "Starts off. Turn it on in HushGram settings > Reels.",
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("aiLabeledReels")
        val page = findReelsPage(PATCH)
        val label = findAiLabel()
        hideAiLabeledReels(page, label)
        enableStatus("aiLabeledReels")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The post's AI label as the extension reads it: Media's [getter] for [AI_LABEL_FIELD], answering
 * the label interface [labelType], and the names [reads] and [text] that interface gives the
 * getters for [READS_LABEL_FIELD] and [LABEL_TEXT_FIELD].
 */
internal class AiLabelSites(val getter: Method, val labelType: String, val reads: String, val text: String)

/**
 * Finds how a post's AI label is read. Media keeps it in one getter, the one method taking nothing
 * that loads the Java hash of [AI_LABEL_FIELD], as Instagram's models read a field from the tree
 * the server sent. That getter answers an interface. Of the classes implementing it, the one whose
 * type name is [AI_LABEL_TYPE_NAME] reads the tree, and its getters for [READS_LABEL_FIELD] and
 * [LABEL_TEXT_FIELD] hold those fields' hashes. The interface declares both, so the extension
 * calls them through it and reads the label whichever class holds it.
 *
 * Fails before anything changes when any of it isn't there exactly once or isn't public.
 */
internal fun BytecodePatchContext.findAiLabel(): AiLabelSites {
    val media = classDefBy(MEDIA)
    val key = AI_LABEL_FIELD.hashCode()
    val getters = media.methods.filter { method ->
        method.parameterTypes.isEmpty() && !AccessFlags.STATIC.isSet(method.accessFlags) && method.loads(key)
    }
    val getter = getters.singleOrNull()
        ?: refuse("expected one getter on $MEDIA for $AI_LABEL_FIELD, found ${getters.map { it.name }}")
    if (!AccessFlags.PUBLIC.isSet(media.accessFlags) || !AccessFlags.PUBLIC.isSet(getter.accessFlags)) {
        refuse("$MEDIA->${getter.name} isn't public, so the extension can't reach it")
    }
    val labelType = getter.returnType
    val label = classDefByOrNull(labelType)
        ?: refuse("$MEDIA->${getter.name} answers $labelType, which isn't a class of the app")
    if (!AccessFlags.INTERFACE.isSet(label.accessFlags) || !AccessFlags.PUBLIC.isSet(label.accessFlags)) {
        refuse("$labelType, which $MEDIA->${getter.name} answers, isn't a public interface")
    }

    val named = classesLoadingString(AI_LABEL_TYPE_NAME).mapTo(HashSet()) { it.type }
    val trees = classesLoading(READS_LABEL_FIELD.hashCode().toLong())
        .filter { it.type in named && labelType in it.interfaces }
    val tree = trees.singleOrNull()
        ?: refuse("expected one $AI_LABEL_TYPE_NAME class implementing $labelType to read $READS_LABEL_FIELD, found ${trees.map { it.type }}")
    val reads = declaredGetter(tree, label, READS_LABEL_FIELD, BOOLEAN)
    val text = declaredGetter(tree, label, LABEL_TEXT_FIELD, STRING)

    val extension = classDefByOrNull(AI_LABELED_REELS) ?: refuse("the extension has no $AI_LABELED_REELS")
    if (extension.methods.none { it.isStaticStub("page", listOf(LIST, STRING), LIST) }) {
        refuse("the extension has no static page(List, String)List")
    }
    listOf("media" to OBJECT, "labelInfo" to OBJECT, "readsLabel" to BOOLEAN, "labelText" to STRING)
        .filter { (stub, returns) -> extension.methods.none { it.isStaticStub(stub, listOf(OBJECT), returns) } }
        .forEach { (stub, returns) -> refuse("the extension has no static $stub(Object)$returns to fill") }

    return AiLabelSites(getter, labelType, reads, text)
}

/** The name of [tree]'s getter for [field] answering [returns], which the interface [label] has to declare. */
private fun declaredGetter(tree: ClassDef, label: ClassDef, field: String, returns: String): String {
    val key = field.hashCode()
    val getters = tree.methods.filter { method ->
        method.parameterTypes.isEmpty() && method.returnType == returns && !AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.loads(key)
    }
    val getter = getters.singleOrNull()
        ?: refuse("expected one getter on ${tree.type} answering $returns for $field, found ${getters.map { it.name }}")
    if (label.methods.none { it.name == getter.name && it.parameterTypes.isEmpty() && it.returnType == returns }) {
        refuse("${label.type} doesn't declare ${getter.name}, the getter for $field")
    }
    return getter.name
}

/**
 * Fills the extension's four stubs, the item's post, the post's label and the label's two fields,
 * then hands each Reels page to [AI_PAGE_FILTER] where the page and its fallback meet.
 */
internal fun BytecodePatchContext.hideAiLabeledReels(page: ReelsPageSites, label: AiLabelSites) {
    fillAiLabelStubs(page, label)
    filterReelsPage(page, AI_PAGE_FILTER)
}

/** Fills the extension's stubs that read a Reels item's post, the post's label and the label's two fields. */
internal fun BytecodePatchContext.fillAiLabelStubs(page: ReelsPageSites, label: AiLabelSites) {
    fillReelsItemMedia(AI_LABELED_REELS, page)
    fillStub(
        AI_LABELED_REELS, "labelInfo", OBJECT, 1,
        """
            check-cast p0, $MEDIA
            invoke-virtual { p0 }, $MEDIA->${label.getter.name}()${label.labelType}
            move-result-object p0
            return-object p0
        """,
    )
    fillStub(AI_LABELED_REELS, "readsLabel", BOOLEAN, 1, labelGetterBody(label.labelType, label.reads, BOOLEAN))
    fillStub(AI_LABELED_REELS, "labelText", STRING, 1, labelGetterBody(label.labelType, label.text, STRING))
}

/** A stub's body that hands its argument, a label, to the interface's [getter] and answers what it reads. */
private fun labelGetterBody(labelType: String, getter: String, returns: String) = """
    check-cast p0, $labelType
    invoke-interface { p0 }, $labelType->$getter()$returns
    move-result-object p0
    return-object p0
"""

/** Whether this method loads [value] as a constant, as a model's getter loads its field's hash. */
private fun Method.loads(value: Int): Boolean = implementation?.instructions?.any {
    (it.opcode == Opcode.CONST || it.opcode == Opcode.CONST_16 || it.opcode == Opcode.CONST_HIGH16 || it.opcode == Opcode.CONST_4) &&
        (it as NarrowLiteralInstruction).narrowLiteral == value
} == true

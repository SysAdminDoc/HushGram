/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.fonts

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

private const val PATCH = "Story text fonts"
internal const val STORY_FONTS = "$EXTENSION_PACKAGE/stories/StoryFonts;"
internal const val FORMATS = "$STORY_FONTS->formats(Ljava/util/List;)V"
internal const val ROW = "$STORY_FONTS->row(Ljava/lang/Object;Ljava/util/List;)I"
internal const val STORY_EMOJI = "$EXTENSION_PACKAGE/stories/StoryEmoji;"
internal const val EMOJI_APPLIED = "$STORY_EMOJI->applied(Landroid/widget/EditText;)V"
internal const val EMOJI_DRAWING = "$STORY_EMOJI->drawing(Ljava/lang/CharSequence;)Ljava/lang/CharSequence;"
internal const val IMPORTED_FONT = "importedFont"
internal const val NATIVE_PREMIUM = "nativePremium"

/** Instagram's name for a format that draws its text in a font file, which its own import gives. */
internal const val IMPORT_FONT = "IMPORT_FONT"

/** What the story text editor's controller logs a failed read of its current format under. */
internal const val TEXT_FORMAT_CONTROLLER = "TextFormatController"

private const val STRING = "Ljava/lang/String;"
private const val INTEGER = "Ljava/lang/Integer;"
private const val EDIT_TEXT = "Landroid/widget/EditText;"
private const val TEXT_VIEW = "Landroid/widget/TextView;"
private const val CHAR_SEQUENCE = "Ljava/lang/CharSequence;"
private const val STATIC_LAYOUT = "Landroid/text/StaticLayout;"
internal const val DRAWABLE = "Landroid/graphics/drawable/Drawable;"
internal const val LAYOUT_BUILDER = "Landroid/text/StaticLayout\$Builder;->obtain(Ljava/lang/CharSequence;IILandroid/text/TextPaint;I)" +
    "Landroid/text/StaticLayout\$Builder;"

/**
 * Adds the font files picked in HushGram settings to the story text editor's row of fonts, before
 * Instagram's paid section. Its own fonts all stay (#275). Each is a format Instagram's import would
 * make for that file, so the editor and the finished sticker draw it the way they draw an imported
 * font. In the default selection with its switch off; adding a font in settings turns it on.
 */
@Suppress("unused")
val storyFontsPatch = bytecodePatch(
    name = "Story text fonts",
    description = "Adds fonts you pick from your phone (.ttf or .otf files) to the story text editor alongside Instagram's " +
        "own fonts, and can draw the emoji in story text from an emoji font file you pick. Starts off. Set both up in " +
        "HushGram settings > Stories.",
) {
    category("Stories")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("storyFonts")
        addStoryFonts()
        enableStatus("storyFonts")
    }
}

/** Instagram's own font import, the story text editor and the format type they share, as this build has them. */
internal class StoryFontTargets(
    /** `(String name, String path)` making a format that names a font file. */
    val named: MethodReference,
    /** The static copy of a format that swaps the fields [mask] leaves clear for the arguments. */
    val copy: MethodReference,
    /** The font kind the import puts in the copy: a font file's. */
    val kind: FieldReference,
    val mask: Int,
    /** The editor's private `()ArrayList` of the formats it offers, rebuilt on every ask. */
    val list: MethodReference,
    /** The static `(format, controller)I` that finds a format's row in [list] by name. */
    val row: MethodReference,
    /** `(EditText, format)V` that applies a format to the text being edited. */
    val apply: MethodReference,
    /** The story text sticker's private `(CharSequence, Z)StaticLayout` that lays out the text it draws. */
    val layout: MethodReference,
    /** Each place a sticker lays out one line of its text to measure it. */
    val lines: List<LineLayout>,
    /** The format's fields the editor names a font from, as [labelFields] finds them. */
    val labels: List<FieldReference>,
    /** The native name and predicate used to locate the first paid row in the editor's list. */
    val premium: FontPremium,
) {
    val format: String get() = copy.returnType
}

/** A sticker method that lays out a line with StaticLayout.Builder, and where it moves the sticker's change to that line into a register. */
internal class LineLayout(val method: MethodReference, val at: Int)

internal class FontPremium(val name: FieldReference, val check: MethodReference)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** Finds everything with [findStoryFontTargets], then fills the extension's stub and adds the hooks. */
internal fun BytecodePatchContext.addStoryFonts() {
    val found = findStoryFontTargets()
    val stub = mutableClassDefBy(STORY_FONTS).methods.singleOrNull {
        it.name == IMPORTED_FONT && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;", "Ljava/lang/Object;") &&
            it.returnType == "Ljava/lang/Object;"
    } ?: refuse("the extension has no $IMPORTED_FONT(Object, Object)Object")
    val premium = mutableClassDefBy(STORY_FONTS).methods.singleOrNull {
        it.name == NATIVE_PREMIUM && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;") &&
            it.returnType == "I"
    } ?: refuse("the extension has no $NATIVE_PREMIUM(Object)int")
    val list = method(found.list)
    val row = method(found.row)
    val apply = method(found.apply)
    row.requireLocals(PATCH, 2)

    // The stub writes the label fields from another package, so they lose private and final.
    val owner = mutableClassDefBy(found.format)
    for (label in found.labels) {
        val field = owner.fields.single { it.name == label.name && it.type == label.type }
        field.accessFlags = field.accessFlags and (AccessFlags.PRIVATE.value or AccessFlags.PROTECTED.value or AccessFlags.FINAL.value).inv() or
            AccessFlags.PUBLIC.value
    }
    val (labelId, descriptionId, labelText, descriptionText) = found.labels

    // The import's format takes Instagram's default font's name, so the copy gets the picked
    // file's name in its place: no string ids, and the name as both texts. v3 is still null here.
    replace(
        stub, 11,
        """
            check-cast p0, $STRING
            const-string v0, "$IMPORT_FONT"
            invoke-static { v0, p0 }, ${found.named}
            move-result-object v2
            sget-object v1, ${found.kind}
            const/4 v0, 0x0
            const/4 v3, 0x0
            const/4 v4, 0x0
            const/4 v5, 0x0
            const/4 v6, 0x0
            const/4 v7, 0x0
            const v8, ${found.mask}
            invoke-static/range { v0 .. v8 }, ${found.copy}
            move-result-object v0
            if-eqz p1, :done
            check-cast p1, $STRING
            iput-object v3, v0, $labelId
            iput-object v3, v0, $descriptionId
            iput-object p1, v0, $labelText
            iput-object p1, v0, $descriptionText
            :done
            return-object v0
        """,
    )

    // Read the native classification; never change it or the controller's entitlement checks.
    replace(
        premium, 2,
        """
            instance-of v0, p0, ${found.format}
            if-eqz v0, :unknown
            check-cast p0, ${found.format}
            iget-object v0, p0, ${found.premium.name}
            if-eqz v0, :unknown
            invoke-static { v0 }, ${found.premium.check}
            move-result v0
            return v0
            :unknown
            const/4 v0, -0x1
            return v0
        """,
    )

    val returns = list.implementation!!.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_OBJECT }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }
    if (returns.isEmpty()) refuse("${found.list} returns nothing")
    returns.asReversed().forEach { (index, register) ->
        list.addInstructionsAtControlFlowLabel(index, "invoke-static/range { v$register .. v$register }, $FORMATS")
    }

    // A lookup by name lands every picked font on the first of them, so the row is asked for first.
    // Nothing has written these locals yet.
    val rowFormat = row.parameterRegister(0)
    val rowController = row.parameterRegister(1)
    row.addInstructionsWithLabels(
        0,
        """
            move-object/from16 v1, $rowFormat
            move-object/from16 v0, $rowController
            invoke-direct { v0 }, ${found.list}
            move-result-object v0
            invoke-static { v1, v0 }, $ROW
            move-result v0
            if-ltz v0, :instagram
            return v0
        """,
        ExternalLabel("instagram", row.getInstruction(0)),
    )

    // The emoji font goes over the text box's emoji whatever the format, so it's told the text box.
    val edit = apply.parameterRegister(0)
    apply.addInstructions(0, "invoke-static/range { $edit .. $edit }, $EMOJI_APPLIED")

    // A finished sticker keeps its own copy of the text without those spans, so the text it lays out
    // to draw, and each line it measures, goes through the emoji font first.
    val layout = method(found.layout)
    val text = layout.parameterRegister(0)
    layout.addInstructions(
        0,
        """
            invoke-static/range { $text .. $text }, $EMOJI_DRAWING
            move-result-object $text
        """,
    )
    // Last first, so two in one method keep their places.
    for (line in found.lines.sortedByDescending { it.at }) {
        val measure = method(line.method)
        val result = (measure.getInstruction(line.at) as OneRegisterInstruction).registerA
        measure.addInstructions(
            line.at + 1,
            """
                invoke-static/range { v$result .. v$result }, $EMOJI_DRAWING
                move-result-object v$result
            """,
        )
    }
}

/**
 * Finds the targets [addStoryFonts] needs, changing nothing. Instagram's own font import loads
 * [IMPORT_FONT], makes a format by that name for the file's path, then copies it with a file font's
 * kind, and that site has to be the only one. The editor's controller is the class whose current
 * format read logs [TEXT_FORMAT_CONTROLLER]: it reads its list of formats, a private `()ArrayList`,
 * and it finds a format's row in that list through one static `(format, controller)I`. The controller
 * applies a format through one static `(EditText, format)V`, and [findStickerLayouts] finds the
 * finished sticker. Anything else is an update this patch hasn't seen, and it fails before anything
 * is written.
 */
internal fun BytecodePatchContext.findStoryFontTargets(): StoryFontTargets {
    val imports = classesLoadingString(IMPORT_FONT).flatMap { it.methods }.flatMap { method -> importSites(method) }
    if (imports.size != 1) refuse("expected Instagram's one font import making an $IMPORT_FONT format, found ${imports.size}")
    val (named, copy, kind, mask) = imports.single()

    val format = copy.returnType
    val controllers = classesLoadingString(TEXT_FORMAT_CONTROLLER).mapNotNull { controller ->
        val current = controller.methods.filter { method ->
            val code = method.code()
            method.parameterTypes.isEmpty() && method.returnType == format &&
                code.indices.any { stringLoadedAt(code, it) == TEXT_FORMAT_CONTROLLER }
        }
        val lists = current.flatMap { it.code() }.mapNotNull { it.call() }.filter {
            it.definingClass == controller.type && it.parameterTypes.isEmpty() && it.returnType == "Ljava/util/ArrayList;"
        }.distinctBy { it.toString() }
        if (current.size == 1 && lists.size == 1) controller to lists.single() else null
    }
    if (controllers.size != 1) {
        refuse("expected one story text editor reading its formats and logging \"$TEXT_FORMAT_CONTROLLER\", found ${controllers.size}")
    }
    val (controller, list) = controllers.single()
    val listMethod = controller.methods.single { it.name == list.name && it.parameterTypes.isEmpty() && it.returnType == list.returnType }
    if (!AccessFlags.PRIVATE.isSet(listMethod.accessFlags) || AccessFlags.STATIC.isSet(listMethod.accessFlags)) {
        refuse("$list is not the editor's own private list of formats")
    }

    val rows = controller.methods.filter { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "I" &&
            method.parameterTypes.map(CharSequence::toString) == listOf(format, controller.type) &&
            method.code().any { it.call()?.toString() == list.toString() }
    }
    if (rows.size != 1) refuse("expected the editor to find a format's row in one place, found ${rows.size}")

    val applies = controller.methods.flatMap { it.code() }.mapNotNull { instruction ->
        instruction.call()?.takeIf {
            instruction.opcode == Opcode.INVOKE_STATIC && it.returnType == "V" &&
                it.parameterTypes.map(CharSequence::toString) == listOf(EDIT_TEXT, format)
        }
    }.distinctBy { it.toString() }
    if (applies.size != 1) refuse("expected the editor to apply a format to its text through one call, found ${applies.size}")

    val (layout, lines) = findStickerLayouts()

    // The extension calls the import's two and reads its kind from another package, and the hook
    // goes in the other one, so all of them have to be there and reachable.
    for (called in listOf(named, copy, applies.single())) {
        val target = declared(called)
        if (!AccessFlags.PUBLIC.isSet(target.accessFlags) || !AccessFlags.STATIC.isSet(target.accessFlags)) {
            refuse("$called is not public and static")
        }
    }
    val kindField = classDefByOrNull(kind.definingClass)?.fields?.singleOrNull { it.name == kind.name && it.type == kind.type }
    if (kindField == null || !AccessFlags.PUBLIC.isSet(kindField.accessFlags) || !AccessFlags.STATIC.isSet(kindField.accessFlags)) {
        refuse("$kind is not a public static field")
    }
    for (type in setOf(format, named.definingClass, kind.definingClass, kind.type)) {
        val owner = classDefByOrNull(type) ?: refuse("$type is not in this build")
        if (!AccessFlags.PUBLIC.isSet(owner.accessFlags)) refuse("$type is not public")
    }
    return StoryFontTargets(named, copy, kind, mask, list, rows.single(), applies.single(), layout, lines,
        labelFields(controller, format), findFontPremium(controller, format, list))
}

/**
 * The row builder reads the post-hook list, classifies names for paid badges, then uses that same
 * predicate to find the first paid index. Clicks and scrolling treat the whole tail from that
 * index as paid (#325), so local formats must go before it. Find the repeated name/predicate pair
 * and the index boxed into a controller field, rather than guessing a boolean on the format.
 */
private fun BytecodePatchContext.findFontPremium(controller: ClassDef, format: String, list: MethodReference): FontPremium {
    val found = controller.methods.flatMap { method ->
        val code = method.code()
        if (code.none { it.call()?.toString() == list.toString() }) return@flatMap emptyList()
        val reads = code.indices.mapNotNull { at ->
            val name = code[at].field()?.takeIf {
                code[at].opcode == Opcode.IGET_OBJECT && it.definingClass == format && it.type == STRING
            } ?: return@mapNotNull null
            val call = code.getOrNull(at + 1)?.call()?.takeIf {
                code[at + 1].opcode == Opcode.INVOKE_STATIC && it.parameterTypes.map(CharSequence::toString) == listOf(STRING) &&
                    it.returnType == "Z" && code[at + 1].arguments() == listOf((code[at] as OneRegisterInstruction).registerA)
            } ?: return@mapNotNull null
            if (code.getOrNull(at + 2)?.opcode != Opcode.MOVE_RESULT ||
                code.getOrNull(at + 3)?.opcode !in setOf(Opcode.IF_EQZ, Opcode.IF_NEZ)
            ) return@mapNotNull null
            at to FontPremium(name, call)
        }
        reads.groupBy { it.second.name.toString() to it.second.check.toString() }.values.mapNotNull { group ->
            val badge = group.any { code[it.first + 3].opcode == Opcode.IF_EQZ }
            val boundary = group.any { (at, _) ->
                val tail = code.subList(at + 4, minOf(code.size, at + 16))
                code[at + 3].opcode == Opcode.IF_NEZ && tail.any {
                    it.call()?.toString() == "$INTEGER->valueOf(I)$INTEGER"
                } && tail.any {
                    it.opcode == Opcode.IPUT_OBJECT && it.field()?.let { field ->
                        field.definingClass == controller.type && field.type == INTEGER
                    } == true
                }
            }
            group.first().second.takeIf { badge && boundary }
        }
    }.distinctBy { it.name.toString() to it.check.toString() }
    if (found.size != 1) refuse("expected one native paid-font predicate and list boundary, found ${found.size}")
    val target = found.single()
    val check = declared(target.check)
    val checker = classDefByOrNull(target.check.definingClass)
    val name = classDefByOrNull(format)?.fields?.singleOrNull { it.name == target.name.name && it.type == STRING }
    if (!AccessFlags.PUBLIC.isSet(check.accessFlags) || !AccessFlags.STATIC.isSet(check.accessFlags) ||
        checker == null || !AccessFlags.PUBLIC.isSet(checker.accessFlags) ||
        name == null || !AccessFlags.PUBLIC.isSet(name.accessFlags) || AccessFlags.STATIC.isSet(name.accessFlags)
    ) refuse("the native paid-font name and predicate are not public")
    return target
}

/**
 * The story text sticker, a drawable whichever editor made it, and where it lays out its text. It's
 * the one drawable with a private `(CharSequence, Z)StaticLayout` that builds with StaticLayout.Builder,
 * which lays out the text it draws, and it changes its text before a layout in one
 * `(CharSequence)CharSequence` of its own. Its family measures each line by handing that change's
 * result straight to StaticLayout.Builder, in at least one place.
 */
private fun BytecodePatchContext.findStickerLayouts(): Pair<MethodReference, List<LineLayout>> {
    val all = mutableListOf<ClassDef>()
    classDefForEach { all += it }
    val byType = all.associateBy { it.type }
    fun drawable(start: ClassDef): Boolean {
        var type: String? = start.superclass
        repeat(8) {
            if (type == DRAWABLE) return true
            type = byType[type ?: return false]?.superclass
        }
        return false
    }
    val layouts = all.filter { drawable(it) }.flatMap { owner ->
        owner.methods.filter { method ->
            AccessFlags.PRIVATE.isSet(method.accessFlags) && !AccessFlags.STATIC.isSet(method.accessFlags) &&
                method.parameterTypes.map(CharSequence::toString) == listOf(CHAR_SEQUENCE, "Z") && method.returnType == STATIC_LAYOUT &&
                method.code().any { it.call()?.toString() == LAYOUT_BUILDER }
        }
    }
    if (layouts.size != 1) refuse("expected one story text sticker laying out its text with StaticLayout.Builder, found ${layouts.size}")
    val layout = layouts.single()
    val sticker = layout.definingClass
    val changes = byType.getValue(sticker).methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.map(CharSequence::toString) == listOf(CHAR_SEQUENCE) &&
            it.returnType == CHAR_SEQUENCE
    }
    if (changes.size != 1) refuse("expected $sticker to change its text before a layout in one method, found ${changes.size}")
    val change = changes.single()

    val family = mutableSetOf(sticker)
    while (true) {
        val more = all.filter { it.superclass in family && it.type !in family }
        if (more.isEmpty()) break
        more.mapTo(family) { it.type }
    }
    val lines = all.filter { it.type in family }.flatMap { owner ->
        owner.methods.flatMap { method ->
            val code = method.code()
            code.indices.filter { at ->
                val called = code[at].call()
                called != null && called.definingClass in family && called.name == change.name &&
                    called.parameterTypes.map(CharSequence::toString) == listOf(CHAR_SEQUENCE) && called.returnType == CHAR_SEQUENCE &&
                    code.getOrNull(at + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT && laysOut(code, at + 1)
            }.map { LineLayout(ImmutableMethodReference(method.definingClass, method.name, method.parameterTypes, method.returnType), it + 1) }
        }
    }
    if (lines.isEmpty()) refuse("expected $sticker and its subclasses to lay out a line of its text with StaticLayout.Builder, found 0")
    return ImmutableMethodReference(sticker, layout.name, layout.parameterTypes, layout.returnType) to lines
}

/**
 * Whether the text [code] moves into a register at [at] goes, untouched and with no branch on the
 * way, into the next StaticLayout.Builder a few instructions on.
 */
private fun laysOut(code: List<Instruction>, at: Int): Boolean {
    val register = (code[at] as OneRegisterInstruction).registerA
    for (next in at + 1 until minOf(code.size, at + 8)) {
        val instruction = code[next]
        if (instruction.call()?.toString() == LAYOUT_BUILDER) return instruction.arguments().firstOrNull() == register
        if (instruction.opcode.setsRegister() && (instruction as? OneRegisterInstruction)?.registerA == register) return false
        if (instruction is OffsetInstruction || instruction.opcode == Opcode.RETURN_OBJECT) return false
    }
    return false
}

/**
 * The [format] fields the editor names a font from: the label's string id, the screen reader's
 * string id, then the label's text and the screen reader's text, each text standing in when its id
 * is null. The editor's label for the current font sets a TextView's text from an id field read just
 * before, or from a text field when that's null. The methods reading that id read one more id, and
 * one more text whose length they check right away, for the screen reader.
 */
private fun BytecodePatchContext.labelFields(controller: ClassDef, format: String): List<FieldReference> {
    fun reads(type: String, then: String, within: Int) = controller.methods.flatMap { method ->
        val code = method.code()
        code.indices.mapNotNull { at ->
            val field = code[at].field()?.takeIf {
                code[at].opcode == Opcode.IGET_OBJECT && it.definingClass == format && it.type == type
            } ?: return@mapNotNull null
            field.takeIf { code.subList(at + 1, minOf(code.size, at + 1 + within)).any { it.call()?.toString() == then } }
        }
    }.distinctBy { it.toString() }

    val labelIds = reads(INTEGER, "$TEXT_VIEW->setText(I)V", 8)
    if (labelIds.size != 1) refuse("expected the editor to show the current font's name from one string id, found ${labelIds.size}")
    val labelTexts = reads(STRING, "$TEXT_VIEW->setText(Ljava/lang/CharSequence;)V", 3)
    if (labelTexts.size != 1) refuse("expected the editor to show the current font's name from one text, found ${labelTexts.size}")
    val labelId = labelIds.single()
    val labelText = labelTexts.single()

    val naming = controller.methods.filter { method -> method.code().any { it.field()?.toString() == labelId.toString() } }
    val readers = naming.flatMap { it.code() }.filter { it.opcode == Opcode.IGET_OBJECT }.mapNotNull { it.field() }
        .filter { it.definingClass == format }
    val descriptionIds = readers.filter { it.type == INTEGER && it.toString() != labelId.toString() }.distinctBy { it.toString() }
    if (descriptionIds.size != 1) refuse("expected the editor's fonts to read one more string id for a screen reader, found ${descriptionIds.size}")
    val descriptionTexts = reads(STRING, "$STRING->length()I", 1)
        .filter { text -> text.toString() != labelText.toString() && readers.any { it.toString() == text.toString() } }
    if (descriptionTexts.size != 1) refuse("expected the editor's fonts to read one more text for a screen reader, found ${descriptionTexts.size}")

    val declared = classDefByOrNull(format)?.fields?.toList().orEmpty()
    val labels = listOf(labelId, descriptionIds.single(), labelText, descriptionTexts.single())
    for (label in labels) {
        val field = declared.singleOrNull { it.name == label.name && it.type == label.type }
        if (field == null || AccessFlags.STATIC.isSet(field.accessFlags)) refuse("$label is not a field of each $format")
    }
    return labels
}

private data class ImportSite(val named: MethodReference, val copy: MethodReference, val kind: FieldReference, val mask: Int)

/**
 * Each place [method] loads [IMPORT_FONT], hands it with a path to a static `(String, String)`
 * making a format, then copies that format with a font kind: a static of the format's own class
 * taking two objects, the format, five strings and a mask, answering a format. The mask is the
 * literal last loaded into the copy's last argument.
 */
private fun BytecodePatchContext.importSites(method: Method): List<ImportSite> {
    val code = method.code()
    return code.indices.filter { stringLoadedAt(code, it) == IMPORT_FONT }.mapNotNull { load ->
        val window = load + 1 until minOf(code.size, load + 20)
        val nameAt = window.firstOrNull { code[it].opcode == Opcode.INVOKE_STATIC } ?: return@mapNotNull null
        val named = code[nameAt].call() ?: return@mapNotNull null
        if (named.parameterTypes.map(CharSequence::toString) != listOf(STRING, STRING) || !named.returnType.startsWith("L")) {
            return@mapNotNull null
        }
        val made = (code.getOrNull(nameAt + 1) as? OneRegisterInstruction)
            ?.takeIf { code[nameAt + 1].opcode == Opcode.MOVE_RESULT_OBJECT }?.registerA ?: return@mapNotNull null
        val format = named.returnType
        val copyAt = (nameAt + 2 until window.last + 1).firstOrNull {
            val call = code[it].call()
            (code[it].opcode == Opcode.INVOKE_STATIC_RANGE || code[it].opcode == Opcode.INVOKE_STATIC) && call != null &&
                call.definingClass == format && call.returnType == format
        } ?: return@mapNotNull null
        val copy = code[copyAt].call()!!
        val takes = copy.parameterTypes.map(CharSequence::toString)
        if (takes.size != 9 || takes[2] != format || takes.subList(3, 8).any { it != STRING } || takes[8] != "I" ||
            !takes[0].startsWith("L") || !takes[1].startsWith("L")
        ) {
            return@mapNotNull null
        }
        val arguments = code[copyAt].arguments()
        if (arguments.size != 9 || arguments[2] != made) return@mapNotNull null
        val kind = (nameAt + 2 until copyAt).lastOrNull {
            code[it].opcode == Opcode.SGET_OBJECT && (code[it] as OneRegisterInstruction).registerA == arguments[1]
        }?.let { code[it].field() } ?: return@mapNotNull null
        if (!kind.type.startsWith("L")) return@mapNotNull null
        val mask = (nameAt + 2 until copyAt).lastOrNull {
            code[it] is NarrowLiteralInstruction && (code[it] as OneRegisterInstruction).registerA == arguments[8]
        }?.let { (code[it] as NarrowLiteralInstruction).narrowLiteral } ?: return@mapNotNull null
        // A set bit keeps that field of the format the name made, and the import changes only the kind.
        if (Integer.bitCount(mask.inv() and (Integer.highestOneBit(mask) * 2 - 1)) != 1) return@mapNotNull null
        ImportSite(named, copy, kind, mask)
    }
}

private fun MethodReference.matches(method: Method) = method.name == name && method.returnType == returnType &&
    method.parameterTypes.map(CharSequence::toString) == parameterTypes.map(CharSequence::toString)

private fun BytecodePatchContext.declared(reference: MethodReference): Method =
    classDefByOrNull(reference.definingClass)?.methods?.singleOrNull { reference.matches(it) }
        ?: refuse("$reference is not in this build")

private fun BytecodePatchContext.method(reference: MethodReference): MutableMethod =
    mutableClassDefBy(reference.definingClass).methods.singleOrNull { reference.matches(it) }
        ?: refuse("$reference is not in this build")

private fun BytecodePatchContext.replace(method: MutableMethod, registers: Int, body: String) {
    val replacement = ImmutableMethod(method.definingClass, method.name, method.parameters, method.returnType,
        method.accessFlags, method.annotations, method.hiddenApiRestrictions,
        ImmutableMethodImplementation(registers, emptyList(), null, null)).toMutable().apply {
        addInstructionsWithLabels(0, body.trimIndent())
    }
    val owner = mutableClassDefBy(method.definingClass)
    owner.methods.remove(method)
    owner.methods.add(replacement)
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Instruction.call() = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.field() = (this as? ReferenceInstruction)?.reference as? FieldReference
private fun Instruction.arguments(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}

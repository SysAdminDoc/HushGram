/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.font

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Font"

internal const val FONTS = "$EXTENSION_PACKAGE/misc/Fonts;"
internal const val PRISM_TYPEFACE = "$FONTS->prismTypeface(Landroid/graphics/Typeface;)Landroid/graphics/Typeface;"
internal const val TEXT_VIEW = "$FONTS->textView(Landroid/widget/TextView;)V"
internal const val RESOURCE_FONT = "$FONTS->resourceFont(Landroid/content/Context;I)Landroid/graphics/Typeface;"
internal const val EMOJI_TYPEFACE = "$FONTS->emojiTypeface(Landroid/graphics/Typeface;)Landroid/graphics/Typeface;"
internal const val EMOJI_NAME = "$FONTS->emojiName(Ljava/lang/String;)Ljava/lang/String;"

/** Instagram's emoji, a name its build keeps, and the image name it gives each emoji picture. */
internal const val IG_EMOJI = "Lcom/instagram/ui/emoji/Emoji;"
internal const val IMAGE_URL = "Lcom/instagram/common/typedurl/SimpleImageUrl;"
internal const val IMAGE_URL_INIT = "$IMAGE_URL-><init>(Ljava/lang/String;)V"

/** What starts the name of a sheet of emoji pictures. No other class loads it. */
internal const val EMOJI_SHEET_MARKER = "emoji-sprite-sheet-api"

/** The name Instagram's own emoji renderer gives the span it builds. No other class holds it. */
internal const val EMOJI_MARKER = "FacebookEmoji"

/** What AndroidX's ResourcesCompat says when a font resource won't load. No other class holds it. */
internal const val FONT_RESOURCE_MARKER = "Font resource ID #0x"

/** What Instagram's Prism font helper logs when a TextView won't take a text appearance. No other class holds it. */
internal const val PRISM_MARKER = "IgdsPrismFontHelper_setTextAppearance_UnsupportedOperationException"

/** Instagram's own TextView, a name its build keeps, and the static that sets each one up. */
internal const val IG_TEXT_VIEW = "Lcom/instagram/common/ui/base/IgTextView;"
internal const val INITIALIZE =
    "Lcom/instagram/common/ui/base/IgTextViewUtils;->initialize(Landroid/widget/TextView;Landroid/content/Context;Landroid/util/AttributeSet;)V"

private const val TYPEFACE = "Landroid/graphics/Typeface;"
private const val CONTEXT = "Landroid/content/Context;"
private const val STRING = "Ljava/lang/String;"

/**
 * Instagram's text draws in the phone's font or a font file picked in HushGram settings. Included
 * in the default selection with Instagram's font chosen, which changes nothing (#166, #5).
 *
 * Instagram's newer design draws its text in Prism Sans. Its font helper loads the regular,
 * semibold and bold weights once each, as it's first used or later on first ask, and keeps them in
 * three static fields that every Prism text reads. The patch hands each Typeface to the extension
 * before it's kept, and it answers the chosen font at the same weight and slant. Each IgTextView,
 * Instagram's own TextView, also goes to the extension right after Instagram sets it up, so text
 * outside Prism takes the chosen font too.
 *
 * Compose text, a profile's bio on 451 among it (#166), loads Prism Sans by itself, from the font
 * resources through AndroidX's ResourcesCompat.getFont(Context, int). The extension answers that
 * call first, with the chosen font for a Prism Sans resource and nothing for any other, which
 * then loads as before.
 *
 * Instagram draws some emoji as pictures rather than text: the row above the comment box, reactions
 * and the like (#166). It draws each in the Typeface its own emoji lookup answers, null on current
 * Android, which means the phone's emoji. Each answer goes through the extension, which gives a
 * picked font file instead, so an emoji font picked as the Font reaches those too. Instagram picks
 * the lookup's class when it starts (its emoji renderer or one that answers nothing), so the patch
 * changes the calls, not either class. Instagram keeps those pictures across restarts by the start of
 * their names, single emoji and sheets of six alike, so each name also goes through the extension
 * before it's used, which adds the picked file's mark there.
 */
@Suppress("unused")
val fontPatch = bytecodePatch(
    name = "Font",
    description = "Lets you pick the font Instagram's text uses: Instagram's own, your phone's, or a .ttf or .otf " +
        "file. Restart Instagram to see a change. Starts on Instagram's font. Pick one in HushGram settings > Layout.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("font")
        swapFonts()
        enableStatus("font")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$PATCH: $why")

/** One write of a Prism Sans Typeface into the helper's fields: where it is and the register written. */
internal class PrismWrite(val method: Method, val at: Int, val register: Int)

/** One call to the emoji lookup: where it is and the register its answer is moved into, the next instruction. */
internal class EmojiCall(val method: Method, val at: Int, val register: Int)

/** Where an emoji picture's name becomes its image name: the constructor call and the register holding the name. */
internal class EmojiNaming(val method: Method, val at: Int, val register: Int)

/** Every method the patch changes, found and checked before anything changes. */
internal class FontSites(
    val writes: List<PrismWrite>,
    val textViewInit: Method,
    val initializeAt: Int,
    val view: Int,
    val getFont: Method,
    val emojiCalls: List<EmojiCall>,
    val emojiNames: List<EmojiNaming>,
)

/**
 * Finds the helper and IgTextView's set-up and checks the extension.
 *
 * The helper is the one class holding [PRISM_MARKER]. Its three static Typeface fields that
 * aren't final are where it keeps Prism Sans, one per weight. Each is written twice, once in its
 * static initializer and once in the instance (Context) Typeface getter that loads it when it's
 * still empty, and nothing else writes them. Any other count means a build this wasn't written for.
 *
 * IgTextView's set-up is the one call in [IG_TEXT_VIEW] to [INITIALIZE], from an instance method,
 * with an instruction after it that nothing jumps to, so whatever runs next has the view in the
 * register the call was given.
 *
 * ResourcesCompat is the one class holding [FONT_RESOURCE_MARKER], and getFont(Context, int) its one
 * static (Context, int) Typeface method. It needs a register of its own for the extension's answer.
 *
 * Instagram's emoji renderer is the one class holding [EMOJI_MARKER] (an abstract base on 452, with the
 * lookup in its one subclass), and its emoji lookup the one instance (String) Typeface method in that
 * class or its subclasses. Instagram calls the lookup through the interface the renderer shares with
 * the class that answers nothing, so the calls found are those to the lookup by the name of any
 * class of that family or any of their interfaces, each with its answer moved into a register
 * by the next instruction. There has to be at least one.
 *
 * A single emoji picture's name comes from the one static (String, String) method of [IG_EMOJI]
 * that answers an [IMAGE_URL], and a sheet's from the one static method answering an [IMAGE_URL]
 * from an [IG_EMOJI] in the classes loading [EMOJI_SHEET_MARKER]. Each makes its [IMAGE_URL] once,
 * from a String in a register, with nothing jumping to that call.
 */
internal fun BytecodePatchContext.findFontSites(): FontSites {
    val helpers = classesHolding(PRISM_MARKER)
    val helper = helpers.singleOrNull() ?: refuse("expected one class holding \"$PRISM_MARKER\", found ${helpers.size}")
    val fields = helper.staticFields.filter { it.type == TYPEFACE && !AccessFlags.FINAL.isSet(it.accessFlags) }
        .map { it.name }.toSet()
    if (fields.size != 3) refuse("${helper.type} keeps ${fields.size} Typefaces that can change, not one per weight")

    val writes = helper.methods.flatMap { method ->
        method.code().withIndex().filter { (_, instruction) -> instruction.writes(helper.type, fields) }
            .map { (at, instruction) -> PrismWrite(method, at, (instruction as OneRegisterInstruction).registerA) }
    }
    for (field in fields) {
        val into = writes.filter { (it.method.code()[it.at] as ReferenceInstruction).field().name == field }
        val inStatic = into.count { it.method.name == "<clinit>" }
        val inGetter = into.count { it.method.isTypefaceGetter() }
        if (into.size != 2 || inStatic != 1 || inGetter != 1) {
            refuse("${helper.type}->$field is written ${into.size} times, not once as it starts and once by its getter")
        }
    }

    val textView = classDefByOrNull(IG_TEXT_VIEW) ?: refuse("this build has no $IG_TEXT_VIEW")
    val calls = textView.methods.flatMap { method ->
        method.code().withIndex().filter { (_, instruction) -> instruction.calls(INITIALIZE) }.map { (at, _) -> method to at }
    }
    val (init, at) = calls.singleOrNull()
        ?: refuse("expected one call to $INITIALIZE in $IG_TEXT_VIEW, found ${calls.size}")
    val where = "${init.definingClass}->${init.name}"
    if (AccessFlags.STATIC.isSet(init.accessFlags)) refuse("$where, which sets up the view, is static")
    val call = init.code()[at]
    if (call.opcode != Opcode.INVOKE_STATIC || call !is FiveRegisterInstruction || call.registerCount != 3) {
        refuse("$where doesn't hand $INITIALIZE the view, its Context and its attributes")
    }
    if (at + 1 >= init.code().size || at + 1 in init.jumpTargets()) {
        refuse("$where has no instruction after $INITIALIZE that only it leads to")
    }

    val compats = classesHolding(FONT_RESOURCE_MARKER)
    val compat = compats.singleOrNull() ?: refuse("expected one class holding \"$FONT_RESOURCE_MARKER\", found ${compats.size}")
    val getters = compat.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == TYPEFACE &&
            it.parameterTypes.map(Any::toString) == listOf(CONTEXT, "I")
    }
    val getFont = getters.singleOrNull()
        ?: refuse("expected one static (Context, int) Typeface in ${compat.type}, found ${getters.size}")
    if (getFont.code().isEmpty() || getFont.localRegisterCount() < 1) {
        refuse("${compat.type}->${getFont.name} has no register free for the extension's answer")
    }

    val renderers = classesHolding(EMOJI_MARKER)
    val renderer = renderers.singleOrNull() ?: refuse("expected one class holding \"$EMOJI_MARKER\", found ${renderers.size}")
    val family = withSubclasses(renderer)
    val lookups = family.flatMap { it.methods }.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == TYPEFACE &&
            it.parameterTypes.map(Any::toString) == listOf(STRING)
    }
    val emojiLookup = lookups.singleOrNull()
        ?: refuse("expected one (String) Typeface in ${renderer.type} or its subclasses, found ${lookups.size}")
    val emojiCalls = family.flatMap { listOf(it.type) + it.interfaces.map(Any::toString) }.distinct().flatMap { contract ->
        val lookup = "$contract->${emojiLookup.name}($STRING)$TYPEFACE"
        classesCalling(contract, emojiLookup.name).flatMap { caller ->
            caller.methods.flatMap { method ->
                val code = method.code()
                code.withIndex().filter { (_, instruction) -> instruction.calls(lookup) }.map { (at, _) ->
                    val moved = code.getOrNull(at + 1)
                    if (moved?.opcode != Opcode.MOVE_RESULT_OBJECT) {
                        refuse("${method.definingClass}->${method.name} doesn't keep the emoji lookup's answer at $at")
                    }
                    EmojiCall(method, at, (moved as OneRegisterInstruction).registerA)
                }
            }
        }
    }
    if (emojiCalls.isEmpty()) refuse("nothing calls ${renderer.type}->${emojiLookup.name}, by itself or its interfaces")

    val emoji = classDefByOrNull(IG_EMOJI) ?: refuse("this build has no $IG_EMOJI")
    val singles = emoji.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == IMAGE_URL &&
            it.parameterTypes.map(Any::toString) == listOf(STRING, STRING)
    }
    val single = singles.singleOrNull()
        ?: refuse("expected one static (String, String) image name in $IG_EMOJI, found ${singles.size}")
    val sheets = classesLoadingString(EMOJI_SHEET_MARKER).flatMap { it.methods }.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == IMAGE_URL && IG_EMOJI in it.parameterTypes.map(Any::toString)
    }
    val sheet = sheets.singleOrNull()
        ?: refuse("expected one static image name for a sheet of emoji, found ${sheets.size}")
    val emojiNames = listOf(single, sheet).map { namer ->
        val where = "${namer.definingClass}->${namer.name}"
        val made = namer.code().withIndex().filter { (_, instruction) -> instruction.calls(IMAGE_URL_INIT) }
        val (at, call) = made.singleOrNull() ?: refuse("$where makes ${made.size} image names, not one")
        if (call.opcode != Opcode.INVOKE_DIRECT || call !is FiveRegisterInstruction || call.registerCount != 2) {
            refuse("$where doesn't make its image name from one register")
        }
        if (at in namer.jumpTargets()) refuse("$where has a jump to where it makes its image name")
        EmojiNaming(namer, at, call.registerD)
    }

    for (hook in listOf(PRISM_TYPEFACE, TEXT_VIEW, RESOURCE_FONT, EMOJI_TYPEFACE, EMOJI_NAME)) {
        if (classDefByOrNull(FONTS)?.methods?.any {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook &&
                    AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
            } != true
        ) {
            refuse("the extension has no public static $hook")
        }
    }
    return FontSites(writes, init, at, call.registerC, getFont, emojiCalls, emojiNames)
}

/**
 * Each Typeface the helper keeps goes through Fonts.prismTypeface first, at the write's own label,
 * since the helper's empty path jumps straight to the write with null. The latest write in each
 * method goes in first, so the earlier ones keep their places. Then Fonts.textView gets each
 * IgTextView right after Instagram sets it up. The range form takes any register. Last,
 * ResourcesCompat.getFont answers the extension's Typeface when there is one, and loads the
 * resource as before when it answers null. Each answer of Instagram's emoji lookup goes through
 * Fonts.emojiTypeface right after it's moved into its register, the latest call in each method first,
 * and each emoji picture's name goes through Fonts.emojiName right before it becomes an image name.
 */
internal fun BytecodePatchContext.swapFonts() {
    val found = findFontSites()
    for ((method, writes) in found.writes.groupBy { it.method }) {
        val mutable = mutable(method)
        for (write in writes.sortedByDescending { it.at }) {
            mutable.addInstructionsAtControlFlowLabel(
                write.at,
                """
                    invoke-static/range { v${write.register} .. v${write.register} }, $PRISM_TYPEFACE
                    move-result-object v${write.register}
                """,
            )
        }
    }
    mutable(found.textViewInit).addInstructions(
        found.initializeAt + 1,
        "invoke-static/range { v${found.view} .. v${found.view} }, $TEXT_VIEW",
    )
    val getFont = mutable(found.getFont)
    getFont.addInstructionsWithLabels(
        0,
        """
            invoke-static { p0, p1 }, $RESOURCE_FONT
            move-result-object v0
            if-eqz v0, :resource
            return-object v0
        """,
        ExternalLabel("resource", getFont.getInstruction(0)),
    )
    for ((method, calls) in found.emojiCalls.groupBy { it.method }) {
        val mutable = mutable(method)
        for (call in calls.sortedByDescending { it.at }) {
            mutable.addInstructions(
                call.at + 2,
                """
                    invoke-static/range { v${call.register} .. v${call.register} }, $EMOJI_TYPEFACE
                    move-result-object v${call.register}
                """,
            )
        }
    }
    for (naming in found.emojiNames) {
        mutable(naming.method).addInstructions(
            naming.at,
            """
                invoke-static/range { v${naming.register} .. v${naming.register} }, $EMOJI_NAME
                move-result-object v${naming.register}
            """,
        )
    }
}

/**
 * [root] and every class below it. Instagram 452 split the emoji renderer in two: the class
 * holding [EMOJI_MARKER] became an abstract base, and the emoji lookup moved to its one subclass.
 */
private fun BytecodePatchContext.withSubclasses(root: ClassDef): List<ClassDef> {
    val all = mutableListOf<ClassDef>()
    classDefForEach { all += it }
    val family = mutableListOf(root)
    while (true) {
        val seen = family.mapTo(HashSet()) { it.type }
        val more = all.filter { it.superclass in seen && it.type !in seen }
        if (more.isEmpty()) return family
        family += more
    }
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }

/** An instance (Context) Typeface: how the helper loads one weight when its field is still empty. */
private fun Method.isTypefaceGetter(): Boolean = !AccessFlags.STATIC.isSet(accessFlags) && returnType == TYPEFACE &&
    parameterTypes.map(Any::toString) == listOf(CONTEXT)

private fun Instruction.writes(type: String, fields: Set<String>): Boolean =
    opcode == Opcode.SPUT_OBJECT && (this as ReferenceInstruction).field().let { it.definingClass == type && it.name in fields }

private fun ReferenceInstruction.field(): FieldReference = reference as FieldReference

private fun Instruction.calls(reference: String): Boolean =
    ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == reference

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

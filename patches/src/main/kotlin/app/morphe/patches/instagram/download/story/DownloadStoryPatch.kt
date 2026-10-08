/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.story

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.download.INSTAGRAM_MEDIA
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.imageBridges
import app.morphe.patches.instagram.download.mediaBridges
import app.morphe.patches.instagram.download.storyMusicBridges
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.originalName
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Download any story"

private const val STORY_DOWNLOAD = "$EXTENSION_PACKAGE/download/StoryDownload;"
internal const val LABELS = "$STORY_DOWNLOAD->labels([Ljava/lang/CharSequence;)[Ljava/lang/CharSequence;"
internal const val SAVE_STORY = "$STORY_DOWNLOAD->save(Ljava/lang/CharSequence;Ljava/lang/Object;)Z"
internal const val BUILDING = "$STORY_DOWNLOAD->building(Ljava/lang/Object;)V"

/** The name Instagram's build keeps, in a static field, for the class that runs a story's menu. */
internal const val HELPER_NAME = "ReelOptionsOverflowHelper"

/** A story, which keeps its name. Instagram calls stories reels, and Reels clips. */
internal const val REEL_ITEM = "Lcom/instagram/model/reels/ReelItem;"
private const val LABEL_ARRAY = "[Ljava/lang/CharSequence;"
private const val LABEL = "Ljava/lang/CharSequence;"
private const val CLICK_LISTENER = "Landroid/content/DialogInterface\$OnClickListener;"
private val CLICK_PARAMETERS = listOf("Landroid/content/DialogInterface;", "I")

/**
 * Download in the menu of anyone's story, saving through HushGram's own pipeline.
 *
 * A story's menu is built as a list of labels by one of a few static builders of the class that
 * runs it, and a tap hands the tapped label, with that class, to one of a few static handlers that
 * compare it with Instagram's own. Instagram offers a save only on your own stories. Each builder
 * first tells the extension which menu it's building, from the parameter that holds it, since a
 * builder can reuse that register before it returns. Its labels get Download added where it returns
 * them (Download as video and Download as photo for a photo story with music), and each handler asks
 * the extension first, which saves the story when the label is one of those. A few older dialogs, shown for special story items,
 * skip the handlers: their click listener looks the tapped label up in a builder's labels itself,
 * so right after that lookup the listener asks the extension too.
 *
 * Everything is found before anything changes, so a build that differs stops the patch naming
 * what it couldn't find, and nothing is half done.
 */
@Suppress("unused")
val downloadStoryPatch = bytecodePatch(
    name = "Download any story",
    description = "Adds Download to the menu of anyone's story. A video saves at the Download quality you set, " +
        "a photo at its largest size. A photo story with music offers Download as video and Download as photo.",
    default = true,
) {
    category("Downloads")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch)

    execute {
        requireStatusMethod("storyDownload")
        offerDownloadOnEveryStory()
        enableStatus("storyDownload")
    }
}

internal fun BytecodePatchContext.offerDownloadOnEveryStory() {
    val helpers = mutableListOf<ClassDef>()
    val listeners = mutableListOf<Method>()
    classDefForEach { classDef ->
        if (classDef.originalName() == HELPER_NAME) helpers += classDef
        if (CLICK_LISTENER in classDef.interfaces) {
            listeners += classDef.methods.filter { method ->
                method.name == "onClick" && method.returnType == "V" && method.parameterTypes.map(Any::toString) == CLICK_PARAMETERS &&
                    method.implementation?.instructions?.any {
                        (it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE) && it.methodReference()?.returnType == LABEL_ARRAY
                    } == true
            }
        }
    }
    val helper = helpers.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one class named $HELPER_NAME, found " + if (helpers.isEmpty()) "none" else helpers.joinToString { it.type },
    )
    val type = helper.type
    val statics = helper.methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null }
    val builders = statics.filter { it.returnType == LABEL_ARRAY && type in it.parameterTypes.map(Any::toString) }
    if (builders.isEmpty()) throw PatchException("$PATCH: $type builds no story menu from its labels")
    val handlers = statics.filter { method ->
        val parameters = method.parameterTypes.map(Any::toString)
        method.returnType == "V" && parameters.lastOrNull() == LABEL && type in parameters
    }
    if (handlers.isEmpty()) throw PatchException("$PATCH: $type handles no tapped label")
    val returns = builders.associateWith { builder ->
        builder.labelReturns().ifEmpty { throw PatchException("$PATCH: $type->${builder.name} never returns its labels") }
    }

    val story = instanceField(type, REEL_ITEM)
    val media = builders.flatMap { builder ->
        builder.implementation!!.instructions.mapNotNull { instruction ->
            if (instruction.opcode != Opcode.IGET_OBJECT) return@mapNotNull null
            val field = (instruction as ReferenceInstruction).reference as FieldReference
            if (field.definingClass == REEL_ITEM && field.type == MEDIA) "$REEL_ITEM->${field.name}:$MEDIA" else null
        }
    }.distinct().singleOrNull() ?: throw PatchException("$PATCH: the story menu's builders don't read one Media of a story")
    val storyMedia = mutableClassDefBy(INSTAGRAM_MEDIA).methods.singleOrNull {
        it.name == "storyMedia" && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;")
    } ?: throw PatchException("$PATCH: $INSTAGRAM_MEDIA has no static storyMedia(Object)")
    val lookups = listeners.flatMap { it.labelLookups(type, builders) }
    val handled = handlers.map { mutable(it) }
    handled.forEach { it.requireLocals(PATCH, 2) }
    val writeVideoBridges = mediaBridges(PATCH)
    val writeImageBridges = imageBridges(PATCH)
    val writeMusicBridges = storyMusicBridges(PATCH)
    // Each builder's menu, its parameter of the menu's class, read before the body can reuse it.
    val menus = builders.associateWith { builder ->
        val parameters = builder.parameterTypes.map(Any::toString)
        val words = parameters.sumOf { if (it == "J" || it == "D") 2 else 1 }
        builder.implementation!!.registerCount - words +
            parameters.take(parameters.indexOf(type)).sumOf { if (it == "J" || it == "D") 2 else 1 }
    }

    // Each return of a builder hands its labels through the extension first. The return is
    // replaced rather than preceded, because a jump to the return lands on what replaces it and
    // would skip anything put in front of it. Then the builder's first instruction tells the
    // extension which menu the labels are for.
    returns.forEach { (builder, found) ->
        val method = mutable(builder)
        found.sortedDescending().forEach { index ->
            val register = (method.getInstruction(index) as OneRegisterInstruction).registerA
            method.replaceInstruction(
                index,
                if (register > 15) "invoke-static/range { v$register .. v$register }, $LABELS" else "invoke-static { v$register }, $LABELS",
            )
            method.addInstructions(
                index + 1,
                """
                    move-result-object v$register
                    return-object v$register
                """,
            )
        }
        val menu = menus.getValue(builder)
        method.addInstructions(
            0,
            if (menu > 15) "invoke-static/range { v$menu .. v$menu }, $BUILDING" else "invoke-static { v$menu }, $BUILDING",
        )
    }

    handled.forEach { method ->
        val parameters = method.parameterTypes.map(Any::toString)
        val first = method.implementation!!.registerCount - parameters.sumOf { if (it == "J" || it == "D") 2 else 1 }
        fun register(index: Int) = first + parameters.take(index).sumOf { if (it == "J" || it == "D") 2 else 1 }
        method.addInstructionsWithLabels(
            0,
            """
                move-object/from16 v0, v${register(parameters.lastIndex)}
                move-object/from16 v1, v${register(parameters.indexOf(type))}
                invoke-static { v0, v1 }, $SAVE_STORY
                move-result v0
                if-eqz v0, :handle
                return-void
            """,
            ExternalLabel("handle", method.getInstruction(0)),
        )
    }

    // Right after a listener's lookup, the label and the menu go to the extension, whose answer
    // takes the array's register, which the listener sets anew next anyway. A yes ends the click;
    // the dialog closes itself.
    lookups.groupBy { it.method }.forEach { (listener, found) ->
        val method = mutable(listener)
        found.sortedByDescending { it.at }.forEach { lookup ->
            method.addInstructionsWithLabels(
                lookup.at + 1,
                """
                    invoke-static { v${lookup.label}, v${lookup.menu} }, $SAVE_STORY
                    move-result v${lookup.spare}
                    if-eqz v${lookup.spare}, :handle
                    return-void
                """,
                ExternalLabel("handle", method.getInstruction(lookup.at + 1)),
            )
        }
    }

    storyMedia.addInstructionsWithLabels(
        0,
        """
            check-cast p0, $type
            iget-object p0, p0, $story
            if-eqz p0, :none
            iget-object p0, p0, $media
            :none
            return-object p0
        """,
    )
    writeVideoBridges()
    writeImageBridges()
    writeMusicBridges()
}

/**
 * In a dialog's click listener, where the tapped label is looked up in a builder's labels: the
 * `aget-object` at [at] leaves it in [label], [menu] holds the story menu's class, and [spare],
 * the array's register, is set anew by the instruction after the lookup.
 */
internal class LabelLookup(val method: Method, val at: Int, val label: Int, val menu: Int, val spare: Int)

/**
 * The lookups in [this], a click listener, of the labels one of [builders], static methods of
 * [type], answers. Each has to be the builder's call, its answer kept, then indexed straight away,
 * and followed by an instruction that sets the array's register without reading it and that no
 * jump lands on. Anything else stops the patch, since a Download label that no tap reaches would
 * do nothing.
 */
internal fun Method.labelLookups(type: String, builders: List<Method>): List<LabelLookup> {
    val code = implementation?.instructions?.toList().orEmpty()
    val where = "$definingClass->$name"
    val calls = code.indices.filter { index ->
        val call = code[index].methodReference() ?: return@filter false
        call.definingClass == type && builders.any { builder ->
            builder.name == call.name && builder.parameterTypes.map(Any::toString) == call.parameterTypes.map(Any::toString)
        }
    }
    if (calls.isEmpty()) return emptyList()
    val address = IntArray(code.size + 1)
    code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
    val targets = code.indices.filter { code[it] is OffsetInstruction && code[it].opcode.name.let { name -> name.startsWith("if-") || name.startsWith("goto") } }
        .map { address.indexOf(address[it] + (code[it] as OffsetInstruction).codeOffset) }
    return calls.map { call ->
        val kept = code.getOrNull(call + 1)
        val lookup = code.getOrNull(call + 2)
        val next = code.getOrNull(call + 3)
        if (kept?.opcode != Opcode.MOVE_RESULT_OBJECT || lookup?.opcode != Opcode.AGET_OBJECT || next == null) {
            throw PatchException("$PATCH: $where doesn't look the tapped label up right after building the labels at $call")
        }
        val array = (kept as OneRegisterInstruction).registerA
        lookup as ThreeRegisterInstruction
        val label = lookup.registerA
        val parameters = code[call].methodReference()!!.parameterTypes.map(Any::toString)
        // A long or a double ahead of the menu takes two registers.
        val menu = code[call].argumentRegisters()[parameters.take(parameters.indexOf(type)).sumOf { if (it == "J" || it == "D") 2 else 1 }]
        val setsArray = next is TwoRegisterInstruction && next.opcode.setsRegister() && next.registerA == array && next.registerB != array ||
            next.opcode in setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.SGET_OBJECT) && (next as OneRegisterInstruction).registerA == array
        if (lookup.registerB != array || label == array || menu == array || menu == label || label > 15 || menu > 15 || !setsArray) {
            throw PatchException("$PATCH: in $where the lookup at ${call + 2} doesn't leave a register this patch can use")
        }
        // A jump onto the answer, the lookup or the instruction after it would reach the check
        // with registers the builder's call never set, or skip it.
        if ((call + 1..call + 3).any { it in targets }) {
            throw PatchException("$PATCH: in $where a jump lands between the labels built at $call and the instruction after the lookup")
        }
        LabelLookup(this, call + 2, label, menu, array)
    }
}

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.argumentRegisters(): List<Int> = when (this) {
    is RegisterRangeInstruction -> List(registerCount) { startRegister + it }
    is Instruction35c -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

/** The returns of [this] builder, each answering its labels. */
internal fun Method.labelReturns(): List<Int> =
    implementation?.instructions?.withIndex()?.filter { it.value.opcode == Opcode.RETURN_OBJECT }?.map { it.index }.orEmpty()

/** The one instance field of [type] in [owner], the story menu's class, as a field reference. */
private fun BytecodePatchContext.instanceField(owner: String, type: String): String {
    val fields = classDefBy(owner).fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == type }
    val field = fields.singleOrNull()
        ?: throw PatchException("$PATCH: expected one $type field in $owner, the story menu's class, found ${fields.size}")
    return "$owner->${field.name}:$type"
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
    }

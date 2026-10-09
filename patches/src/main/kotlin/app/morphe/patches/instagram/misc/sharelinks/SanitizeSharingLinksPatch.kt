/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.sharelinks

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.analytics.loadsString
import app.morphe.patches.instagram.misc.analytics.stringLoadedAt
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.handleTargets
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.writeTargetCoverage
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.sendToStandIn
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Sanitize sharing links"

private const val CLEANER = "$EXTENSION_PACKAGE/misc/LinkCleaner;"

internal const val SANITIZE = "$CLEANER->sanitizeShared(Ljava/lang/String;)Ljava/lang/String;"

internal const val BROWSER_LINK = "$CLEANER->browserLink(Ljava/lang/String;)Ljava/lang/String;"

/**
 * A framework call a link leaves Instagram through, and whether it's an instance call, whose
 * stand-in takes the receiver first.
 */
internal data class LinkExit(val definingClass: String, val name: String, val shape: String, val virtual: Boolean) {
    /** The LinkCleaner method of the same name that stands in for this call. */
    val standIn: String
        get() {
            val parameters = shape.substringAfter('(').substringBefore(')')
            val receiver = if (virtual) definingClass else ""
            return "$CLEANER->$name($receiver$parameters)${shape.substringAfter(')')}"
        }

    fun matches(instruction: Instruction): Boolean {
        val expected = if (virtual) {
            instruction.opcode == Opcode.INVOKE_VIRTUAL || instruction.opcode == Opcode.INVOKE_VIRTUAL_RANGE
        } else {
            instruction.opcode == Opcode.INVOKE_STATIC || instruction.opcode == Opcode.INVOKE_STATIC_RANGE
        }
        if (!expected) return false
        val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return call.definingClass == definingClass && call.name == name &&
            call.parameterTypes.joinToString("", "(", ")") + call.returnType == shape
    }
}

/** Every copy to the clipboard: Instagram builds the clip as plain text and sets it here. */
internal val CLIPBOARD_EXITS = listOf(
    LinkExit("Landroid/content/ClipboardManager;", "setPrimaryClip", "(Landroid/content/ClipData;)V", virtual = true),
)

/** Every system share sheet: the shared text rides in the target intent's EXTRA_TEXT. */
internal val SHARE_SHEET_EXITS = listOf(
    LinkExit(
        "Landroid/content/Intent;", "createChooser",
        "(Landroid/content/Intent;Ljava/lang/CharSequence;)Landroid/content/Intent;", virtual = false,
    ),
    LinkExit(
        "Landroid/content/Intent;", "createChooser",
        "(Landroid/content/Intent;Ljava/lang/CharSequence;Landroid/content/IntentSender;)Landroid/content/Intent;",
        virtual = false,
    ),
)

/**
 * Every activity Instagram starts. A share straight to one app, the row of app icons in Instagram's
 * own share sheet among them, is an ACTION_SEND intent with no share sheet in between, and it leaves
 * through here whichever way its text was filled in. The stand-ins clean only ACTION_SEND intents.
 * Instagram's launchers all end in these two; it never starts an activity through Activity's own.
 */
internal val DIRECT_SHARE_EXITS = listOf(
    LinkExit("Landroid/content/Context;", "startActivity", "(Landroid/content/Intent;)V", virtual = true),
    LinkExit(
        "Landroid/content/Context;", "startActivity",
        "(Landroid/content/Intent;Landroid/os/Bundle;)V", virtual = true,
    ),
)

@Suppress("unused")
val sanitizeSharingLinksPatch = bytecodePatch(
    name = "Sanitize sharing links",
    description = "Removes tracking tags from the links you copy or share, and opens bio links without " +
        "Instagram's click tracker. The link still opens the same post, reel, story or profile. On by default. " +
        "Turn it off in HushGram settings > Ads and privacy.",
    default = true,
) {
    category("Privacy")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch)

    execute {
        requireStatusMethod("sanitizeSharingLinks")

        val targets = listOf(
            "permalink parser", "story link parser", "clipboard copies", "share sheets", "direct shares",
            "in-app browser menu",
        )
        handleTargets(PATCH, "ways a link leaves Instagram", targets,
            coverage = { writeTargetCoverage("sanitizeSharingLinks", it) }) { target ->
            when (target) {
                "permalink parser" -> cleanPermalink()
                "story link parser" -> cleanStoryLink()
                "clipboard copies" -> if (rerouteLinkExits(CLIPBOARD_EXITS) > 0) null
                    else "no code calls ClipboardManager.setPrimaryClip"
                "share sheets" -> if (rerouteLinkExits(SHARE_SHEET_EXITS) > 0) null
                    else "no code calls Intent.createChooser"
                "direct shares" -> if (rerouteLinkExits(DIRECT_SHARE_EXITS) > 0) null
                    else "no code calls Context.startActivity"
                else -> cleanBrowserMenu()
            }
        }

        enableStatus("sanitizeSharingLinks")
    }
}

/**
 * Cleans the link a story's share link parser reads, the [STORY_SHARE_URL_FIELD] field. The parser
 * is the one unsafeParseFromJson answering an object that loads [STORY_SHARE_URL_TYPE]. Redex asks
 * a pool of shared strings for that name in 450's 385611395 and 385611400, where 385611438 loads it
 * itself (#77), and for the field's name in all of them, so both are read through the pools.
 */
internal fun BytecodePatchContext.cleanStoryLink(): String? =
    sanitizeParsedLink(parsersLoading(STORY_SHARE_URL_TYPE), STORY_SHARE_URL_TYPE, STORY_SHARE_URL_FIELD)

/**
 * Cleans the link a post or reel's copy-link answer carries, in the one unsafeParseFromJson
 * answering an object that loads both [PERMALINK_FIELD] and [PERMALINK_TYPE]. Redex asks a pool of
 * shared strings for the type name in 450's x86 build (385611439), where the others load it
 * themselves (#95), so both are read through the pools.
 */
internal fun BytecodePatchContext.cleanPermalink(): String? =
    sanitizeParsedLink(parsersLoading(PERMALINK_TYPE, PERMALINK_FIELD), PERMALINK_TYPE)

/**
 * The unsafeParseFromJson methods answering an object that load [typeName] and each of [fields],
 * themselves or from a pool of shared strings, as mutable methods.
 */
private fun BytecodePatchContext.parsersLoading(typeName: String, vararg fields: String): List<MutableMethod> =
    classesLoadingString(typeName).flatMap { it.methods }.filter { method ->
        method.name == "unsafeParseFromJson" && method.returnType == "Ljava/lang/Object;" &&
            loadsString(method, typeName) && fields.all { loadsString(method, it) }
    }.map { parser ->
        mutableClassDefBy(parser.definingClass).methods.single {
            it.name == parser.name && it.returnType == parser.returnType &&
                it.parameterTypes.map(Any::toString) == parser.parameterTypes.map(Any::toString)
        }
    }

/**
 * Cleans the link a share-link parser reads, just before it's stored in the model the parser
 * builds. The model is named by [typeName], loaded right before it's made, and the link is the first
 * String field stored after that. Answers null when done, or why not.
 */
private fun BytecodePatchContext.sanitizeParsedLink(parsers: List<MutableMethod>, typeName: String, field: String? = null): String? {
    if (parsers.size != 1) return "expected one parser naming $typeName, found ${parsers.size}"
    val parser = parsers.single()
    if (field != null && !loadsString(parser, field)) return "the parser naming $typeName doesn't read \"$field\""
    val store = linkStore(parser, typeName)
        ?: return "the parser naming $typeName stores no String field after loading its type name"
    val (index, register) = store
    parser.addInstructions(
        index,
        """
            invoke-static/range { v$register .. v$register }, $SANITIZE
            move-result-object v$register
        """,
    )
    return null
}

/**
 * Hands the page address the in-app browser's menu sends for Share and for Copy link to
 * LinkCleaner.browserLink, in the main app's handler for those messages, right before the share puts
 * it in its text and the copy hands it to Instagram's clipboard helper. Each branch reads the address
 * from the message, or logs its failure line and falls back to an empty one, and the two paths meet
 * before that call, so the hook sits on both. Everything is checked before anything changes.
 * Answers null when done, or why not.
 */
internal fun BytecodePatchContext.cleanBrowserMenu(): String? {
    val matches = BrowserMenuHandlerFingerprint.matchAllOrNull().orEmpty()
    if (matches.size != 1) return "expected one handler for the in-app browser's menu, found ${matches.size}"
    val handler = matches.single().method
    val share = handler.browserLinkUse(BROWSER_SHARE_FAILURE, ::putsSharedText)
        ?: return "the browser menu's Share puts no address in a share's text"
    val copy = handler.browserLinkUse(BROWSER_COPY_FAILURE, ::copiesText)
        ?: return "the browser menu's Copy link hands no address to a clipboard helper"
    if (share.first == copy.first) return "the browser menu's Share and Copy link meet in one call"
    for ((index, register) in listOf(share, copy).sortedByDescending { it.first }) {
        handler.addInstructions(
            index,
            """
                invoke-static/range { v$register .. v$register }, $BROWSER_LINK
                move-result-object v$register
            """,
        )
    }
    return null
}

/**
 * Where the branch logging [failure] hands its address on: the first call after the empty address
 * the branch falls back to that takes that register, with the register, when [takes] says it's the
 * call the address is for. Null when there's no such call, it's another call, something branches
 * straight to it past the hook, or the register is too high to write back.
 */
private fun MutableMethod.browserLinkUse(failure: String, takes: (List<Instruction>, Int, Int) -> Boolean): Pair<Int, Int>? {
    val instructions = implementation!!.instructions
    val logged = instructions.indexOfFirst { it.loadsText(failure) }
    if (logged < 0) return null
    val fallback = (logged + 1 until instructions.size).firstOrNull { instructions[it].loadsText("") } ?: return null
    val register = (instructions[fallback] as OneRegisterInstruction).registerA
    if (register > 255) return null
    val use = (fallback + 1 until instructions.size).firstOrNull { register in instructions[it].argumentRegisters() }
        ?: return null
    if (!takes(instructions, use, register)) return null
    if ((instructions[use] as BuilderInstruction).location.labels.isNotEmpty()) return null
    return use to register
}

/** Whether the call at [index] puts [register] in an intent's EXTRA_TEXT, its key loaded right before. */
private fun putsSharedText(instructions: List<Instruction>, index: Int, register: Int): Boolean {
    val call = instructions[index]
    val method = (call as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    if (call.opcode != Opcode.INVOKE_VIRTUAL && call.opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
    if (method.definingClass != "Landroid/content/Intent;" || method.name != "putExtra") return false
    if (method.parameterTypes.map(Any::toString) !in listOf(
            listOf("Ljava/lang/String;", "Ljava/lang/String;"),
            listOf("Ljava/lang/String;", "Ljava/lang/CharSequence;"),
        )
    ) return false
    val (_, key, value) = call.argumentRegisters()
    if (value != register || index == 0) return false
    val before = instructions[index - 1]
    return before.loadsText("android.intent.extra.TEXT") && (before as OneRegisterInstruction).registerA == key
}

/** Whether the call at [index] is a static (Context, String) helper taking [register] as its String. */
private fun copiesText(instructions: List<Instruction>, index: Int, register: Int): Boolean {
    val call = instructions[index]
    val method = (call as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    if (call.opcode != Opcode.INVOKE_STATIC && call.opcode != Opcode.INVOKE_STATIC_RANGE) return false
    if (method.parameterTypes.map(Any::toString) != listOf("Landroid/content/Context;", "Ljava/lang/String;")) return false
    return call.argumentRegisters()[1] == register
}

private fun Instruction.loadsText(text: String): Boolean =
    (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
        ((this as ReferenceInstruction).reference as StringReference).string == text

/** The registers an invoke hands over, in order. */
private fun Instruction.argumentRegisters(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}

/**
 * The index and source register of the first `iput-object` of a String after [typeName] is loaded,
 * by the parser itself or from a pool of shared strings.
 */
internal fun BytecodePatchContext.linkStore(method: Method, typeName: String): Pair<Int, Int>? {
    val instructions = method.implementation?.instructions?.toList() ?: return null
    val load = instructions.indices.firstOrNull { stringLoadedAt(instructions, it) == typeName } ?: return null
    for (index in load + 1 until instructions.size) {
        val instruction = instructions[index]
        if (instruction.opcode != Opcode.IPUT_OBJECT) continue
        val field = (instruction as ReferenceInstruction).reference as FieldReference
        if (field.type == "Ljava/lang/String;") return index to (instruction as TwoRegisterInstruction).registerA
    }
    return null
}

/**
 * Sends each call to one of [exits] in Instagram's own code to its LinkCleaner stand-in, which
 * cleans the text and makes the real call. Answers how many calls it sent.
 */
internal fun BytecodePatchContext.rerouteLinkExits(exits: List<LinkExit>): Int {
    val owners = mutableListOf<String>()
    val calling = exits.flatMapTo(HashSet()) { exit -> classesCalling(exit.definingClass, exit.name).map { it.type } }
    classDefForEach { classDef ->
        if (classDef.type !in calling || classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        if (classDef.methods.any { method -> method.exitSites(exits).isNotEmpty() }) owners += classDef.type
    }
    return owners.sumOf { type ->
        mutableClassDefBy(type).methods.sumOf { method -> method.rerouteExits(exits) }
    }
}

private fun MutableMethod.rerouteExits(exits: List<LinkExit>): Int {
    val sites = exitSites(exits)
    sites.asReversed().forEach { (index, exit) -> sendToStandIn(index, exit.standIn) }
    return sites.size
}

private fun Method.exitSites(exits: List<LinkExit>): List<Pair<Int, LinkExit>> =
    implementation?.instructions?.withIndex()?.mapNotNull { (index, instruction) ->
        exits.firstOrNull { it.matches(instruction) }?.let { index to it }
    }.orEmpty()

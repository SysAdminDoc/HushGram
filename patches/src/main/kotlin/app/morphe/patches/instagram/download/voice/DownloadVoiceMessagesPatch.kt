/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.voice

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
import app.morphe.patches.instagram.misc.extension.classesCreating
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

internal const val VOICE_MESSAGE_PATCH = "Download voice messages"
internal const val VOICE_MESSAGE = "$EXTENSION_PACKAGE/download/VoiceMessage;"
internal const val OFFER_VOICE = "$VOICE_MESSAGE->offer(ILjava/lang/Object;)Z"
internal const val SAVE_VOICE = "$VOICE_MESSAGE->save(Landroid/content/Context;Ljava/lang/Object;)Z"
internal const val AUDIO_BRIDGE = "audio"
internal const val VIEW_MODE_BRIDGE = "viewMode"

/** The trace name of the method that builds a chat message's long-press menu. */
internal const val MESSAGE_MENU = "DirectThreadFragment.showMessageActionDialog"

/** What the menu's Save action calls itself. */
internal const val SAVE_ACTION = "SaveMedia"

/** The trace step right before Instagram reads a voice message's recording address for its player. */
internal const val AUDIO_SOURCE = "prepare audio source link"

/** Two of the keys a voice message's media is parsed from. */
internal const val VIEW_MODE_KEY = "view_mode"
internal const val WAVEFORM_KEY = "waveform_data"

/** The kept name of the chat's media saver, and the message its check of a message's kind throws. */
internal const val MEDIA_SAVER = "DirectThreadMediaSaver"
internal const val SAVER_CHECK = "Invalid message contentType: "

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val ACTIVITY = "Landroid/app/Activity;"
private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

/** The one-register copies a lookup's arguments may be put in place with. */
private val COPIES = setOf(
    Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16,
    Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16,
)

/**
 * Save in a voice message's long-press menu, saving the recording through HushGram's own save.
 * Included in the default selection with its switch off.
 */
@Suppress("unused")
val downloadVoiceMessagesPatch = bytecodePatch(
    name = "Download voice messages",
    description = "Adds Save to the menu you get when you hold a voice message in a chat. It saves the recording " +
        "as an audio file. Starts off. Turn it on in HushGram settings > Downloads.",
    default = true,
) {
    category("Downloads")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())
    execute {
        requireStatusMethod("voiceMessage")
        val found = findVoiceMessages()
        applyVoiceMessages(found)
        enableStatus("voiceMessage")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$VOICE_MESSAGE_PATCH: $why")

/**
 * Where the menu builder asks a message's kind whether it can be saved: [at] is the `if-eqz` on the
 * answer in [answer], and [message] holds the message it asked about.
 */
internal class VoiceOffer(val method: MutableMethod, val at: Int, val answer: Int, val message: Int)

/**
 * The saver's entry, [method], with the declared parameters holding the saver and the request, the
 * request's message field, the saver's activity field and two locals the hook may use at its top.
 */
internal class VoiceSaver(
    val method: MutableMethod,
    val saver: Int,
    val request: Int,
    val message: FieldReference,
    val activity: FieldReference,
    val free: List<Int>,
)

/**
 * The reads the bridges make off a [message]: its [voice] media, that media's [media] and
 * [source], the recording's [address], and the voice media's [viewMode].
 */
internal class VoiceReads(
    val message: String,
    val voice: FieldReference,
    val media: Instruction,
    val source: FieldReference,
    val address: Instruction,
    val viewMode: FieldReference,
)

internal class VoiceMessages(val offer: VoiceOffer, val saver: VoiceSaver, val reads: VoiceReads)

/** Finds every place the patch changes, and every read its bridges make, before anything changes. */
internal fun BytecodePatchContext.findVoiceMessages(): VoiceMessages {
    stubs()
    val (message, flag) = findSaveFlag()
    val offer = findVoiceOffer(message, flag)
    val reads = findVoiceReads(message)
    val saver = findVoiceSaver(message)
    return VoiceMessages(offer, saver, reads)
}

/**
 * The menu's message type and the boolean of the menu's model that decides whether Save goes in.
 * The menu method holds [MESSAGE_MENU] and hands it to the call that looks the message up, whose
 * return type is the message's. Each time the menu adds the Save action (the one singleton whose
 * toString is [SAVE_ACTION]) it first tests that boolean, read off one of the menu's parameters.
 *
 * 438 calls the lookup right after loading the name. The x86_64 build 385611440 calls a static
 * copy of it taking one more argument, and copies that argument into place in between, so copies
 * of other registers before the call are passed over.
 */
internal fun BytecodePatchContext.findSaveFlag(): Pair<String, FieldReference> {
    val menus = classesHolding(MESSAGE_MENU).flatMap { host -> host.methods.filter { MESSAGE_MENU in it.strings() } }
    val menu = menus.singleOrNull() ?: refuse("expected one method loading \"$MESSAGE_MENU\", found ${menus.size}")
    val code = menu.code()
    val loads = code.indices.filter { (code[it].reference() as? StringReference)?.string == MESSAGE_MENU }
    val load = loads.singleOrNull() ?: refuse("expected one load of \"$MESSAGE_MENU\", found ${loads.size}")
    val name = (code[load] as OneRegisterInstruction).registerA
    var at = load + 1
    while (code.getOrNull(at)?.let { it.opcode in COPIES && (it as OneRegisterInstruction).registerA != name } == true) at++
    val lookup = code.getOrNull(at)
    val message = lookup?.call()?.returnType
    if (lookup?.call() == null || name !in lookup.arguments() ||
        message == null || !message.startsWith("L") || code.getOrNull(at + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT
    ) refuse("\"$MESSAGE_MENU\" isn't handed to the call that looks the message up")

    // Other menus have a Save action of their own; this menu reads one of them.
    val named = classesHolding(SAVE_ACTION).filter { action ->
        action.fields.any { AccessFlags.STATIC.isSet(it.accessFlags) && it.type == action.type } &&
            action.methods.any { it.name == "toString" && it.returnType == STRING && SAVE_ACTION in it.strings() }
    }.mapTo(HashSet()) { it.type }
    val singletons = code.indices.filter { at ->
        code[at].opcode == Opcode.SGET_OBJECT && code[at].field()?.let { it.definingClass == it.type && it.type in named } == true
    }
    val actions = singletons.mapTo(HashSet()) { code[it].field()!!.type }
    val action = actions.singleOrNull() ?: refuse("expected the menu to add one Save action named \"$SAVE_ACTION\", found ${actions.size}")
    val sites = singletons.filter { code[it].field()!!.type == action }

    val models = menu.parameters().toSet()
    val flags = mutableSetOf<String>()
    var flag: FieldReference? = null
    val flagRegisters = mutableSetOf<Int>()
    val guards = sites.map { site ->
        var at = site - 1
        val tests = mutableListOf<Instruction>()
        while (at >= 0 && (code[at].opcode == Opcode.IF_EQZ || code[at].opcode == Opcode.IF_NEZ)) tests += code[at--]
        val read = code.getOrNull(at)
        val field = read?.field()
        if (read?.opcode == Opcode.IGET_BOOLEAN && field != null && field.definingClass in models) {
            flags += field.toString()
            flag = field
            flagRegisters += (read as TwoRegisterInstruction).registerA
        }
        tests
    }
    if (flags.size != 1) refuse("expected one boolean of the menu's model guarding Save, found ${flags.size}")
    guards.forEachIndexed { index, tests ->
        if (tests.none { it.opcode == Opcode.IF_EQZ && (it as OneRegisterInstruction).registerA in flagRegisters }) {
            refuse("Save at instruction ${sites[index]} isn't added only when ${flag} is true")
        }
    }
    return message!! to flag!!
}

/**
 * Where the model's [flag] is worked out: the model's constructor stores one of its boolean
 * parameters in it, and the one method building the model hands that parameter a register it sets
 * to 1 or 0. Right before it's set to 1, the builder asks an interface whether the message's kind
 * can be saved, with the account and the message, and a no goes straight to setting it to 0. That
 * `if-eqz` is where the hook goes.
 */
internal fun BytecodePatchContext.findVoiceOffer(message: String, flag: FieldReference): VoiceOffer {
    val model = classDefByOrNull(flag.definingClass) ?: refuse("the menu's model ${flag.definingClass} isn't in the app")
    val stores = model.methods.filter { it.name == "<init>" }.flatMap { init ->
        val code = init.code()
        code.indices.filter { code[it].opcode == Opcode.IPUT_BOOLEAN && code[it].field()?.toString() == flag.toString() }.map { init to it }
    }
    val (init, store) = stores.singleOrNull() ?: refuse("expected one constructor storing $flag, found ${stores.size}")
    val parameter = init.storedParameter(store)
    if (init.parameters()[parameter] != "Z") refuse("$flag is stored from a parameter that isn't a boolean")
    val slot = init.parameterRegister(parameter).removePrefix("p").toInt()
    val initParameters = init.parameters()

    val candidates = classesCreating(model.type).flatMap { builder ->
        builder.methods.flatMap { method ->
            val code = method.code()
            code.indices.filter { at ->
                val call = code[at].call()
                (code[at].opcode == Opcode.INVOKE_DIRECT || code[at].opcode == Opcode.INVOKE_DIRECT_RANGE) &&
                    call?.definingClass == model.type && call.name == "<init>" && call.parameters() == initParameters
            }.mapNotNull { at -> offerAt(builder, method, code, code[at].arguments()[slot], message) }
        }
    }
    val (builder, method, at) = candidates.distinct().singleOrNull()
        ?: refuse("expected one place the model's builder asks whether a message can be saved, found ${candidates.size}")
    val mutable = mutableClassDefBy(builder.type).methods.single {
        it.name == method.name && it.parameters() == method.parameters() && it.returnType == method.returnType
    }
    val test = mutable.getInstruction(at) as OneRegisterInstruction
    val ask = mutable.getInstruction(at - 2)
    val held = ask.arguments()[2]
    if (test.registerA > 15 || held > 15) refuse("the builder keeps the answer or the message in a register over v15")
    if (test.registerA == held) refuse("the builder's answer lands in the message's register")
    if (at in mutable.jumpTargets()) refuse("something jumps to the builder's check of the answer, so the hook would be skipped")
    return VoiceOffer(mutable, at, test.registerA, held)
}

/**
 * The index of the `if-eqz` in [method] testing the answer to whether [message]'s kind can be saved,
 * when [flag] (the register handed to the model's boolean) is set to 1 right after that check and
 * the `if-eqz` goes to where it's set to 0. Null when this call of the constructor isn't that.
 */
private fun offerAt(builder: ClassDef, method: Method, code: List<Instruction>, flag: Int, message: String): Triple<ClassDef, Method, Int>? {
    val sets = code.indices.filter { code[it].setsLiteral(flag, 1) }
    val set = sets.singleOrNull() ?: return null
    val asks = (0 until set).filter { at ->
        val call = code[at].call()
        code[at].opcode == Opcode.INVOKE_INTERFACE && call?.returnType == "Z" && call.parameters() == listOf(USER_SESSION, message)
    }
    val ask = asks.lastOrNull() ?: return null
    val result = code.getOrNull(ask + 1)
    val test = code.getOrNull(ask + 2)
    if (result?.opcode != Opcode.MOVE_RESULT || test?.opcode != Opcode.IF_EQZ ||
        (test as OneRegisterInstruction).registerA != (result as OneRegisterInstruction).registerA
    ) return null
    val no = ControlFlow.of(method).normal[ask + 2].firstOrNull() ?: return null
    if (!code[no].setsLiteral(flag, 0)) return null
    return Triple(builder, method, ask + 2)
}

/**
 * The declared parameter the constructor's store at [store] takes its value from, through any moves
 * in between. Everything up to the store runs straight through, so the last write before it is the
 * one it sees.
 */
private fun Method.storedParameter(store: Int): Int {
    val code = code()
    if (jumpTargets().any { it <= store }) refuse("$definingClass's constructor branches before it stores the flag")
    var register = (code[store] as TwoRegisterInstruction).registerA
    for (at in store - 1 downTo 0) {
        val instruction = code[at]
        val written = (instruction as? OneRegisterInstruction)?.registerA
        if (!instruction.opcode.setsRegister() || written == null) continue
        if (instruction.opcode.setsWideRegister() && written + 1 == register) refuse("$definingClass's constructor writes over the flag's register")
        if (written != register) continue
        if (instruction.opcode !in setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)) {
            refuse("$definingClass's constructor doesn't store the flag straight from a parameter")
        }
        register = (instruction as TwoRegisterInstruction).registerB
    }
    val locals = localRegisterCount()
    val self = if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    val parameter = parameters().indices.firstOrNull { parameterRegister(it) == "p${register - locals}" }
    if (register < locals + self || parameter == null) refuse("$definingClass's constructor doesn't store the flag from a parameter")
    return parameter
}

/**
 * The reads off a message the bridges make. Right after [AUDIO_SOURCE], Instagram's own player
 * reads the message's voice media, its media, that media's source and the source's address, each
 * off the one before. The voice media's view mode is what its parser, the one method making the
 * voice media while holding [VIEW_MODE_KEY] and [WAVEFORM_KEY], stores the string read for
 * [VIEW_MODE_KEY] in.
 */
internal fun BytecodePatchContext.findVoiceReads(message: String): VoiceReads {
    val players = classesHolding(AUDIO_SOURCE).flatMap { host -> host.methods.filter { AUDIO_SOURCE in it.strings() } }
    val player = players.singleOrNull() ?: refuse("expected one method loading \"$AUDIO_SOURCE\", found ${players.size}")
    val code = player.code()
    val from = code.indexOfFirst { (it.reference() as? StringReference)?.string == AUDIO_SOURCE }

    fun next(after: Int, what: String, test: (Instruction) -> Boolean): Int =
        (after + 1..minOf(after + 6, code.lastIndex)).firstOrNull { test(code[it]) } ?: refuse("the player doesn't read $what")

    val voiceAt = next(from, "the message's voice media") {
        it.opcode == Opcode.IGET_OBJECT && it.field()?.definingClass == message && it.field()!!.type.startsWith("L")
    }
    val voice = code[voiceAt].field()!!
    val mediaAt = next(voiceAt, "the voice media's media") {
        it.isGetter(voice.type) && it.call()!!.returnType.startsWith("L") && it.arguments() == listOf((code[voiceAt] as TwoRegisterInstruction).registerA)
    }
    val media = code[mediaAt]
    val held = (code.getOrNull(mediaAt + 1) as? OneRegisterInstruction)?.registerA
    if (code[mediaAt + 1].opcode != Opcode.MOVE_RESULT_OBJECT) refuse("the player doesn't keep the voice media's media")
    val sourceAt = next(mediaAt + 1, "the media's source") {
        it.opcode == Opcode.IGET_OBJECT && it.field()?.definingClass == media.call()!!.returnType && (it as TwoRegisterInstruction).registerB == held
    }
    val source = code[sourceAt].field()!!
    val addressAt = next(sourceAt, "the recording's address") {
        it.isGetter(source.type) && it.call()!!.returnType == STRING && it.arguments() == listOf((code[sourceAt] as TwoRegisterInstruction).registerA)
    }

    val parsers = classesHolding(VIEW_MODE_KEY, WAVEFORM_KEY).flatMap { host ->
        host.methods.filter { method ->
            VIEW_MODE_KEY in method.strings() && method.code().any { it.opcode == Opcode.NEW_INSTANCE && (it.reference() as? TypeReference)?.type == voice.type }
        }
    }
    val parser = parsers.singleOrNull() ?: refuse("expected one parser of the voice media ${voice.type}, found ${parsers.size}")
    val parsed = parser.code()
    val key = parsed.indexOfFirst { (it.reference() as? StringReference)?.string == VIEW_MODE_KEY }
    val readAt = (key + 1..minOf(key + 6, parsed.lastIndex)).firstOrNull { parsed[it].opcode == Opcode.MOVE_RESULT_OBJECT }
        ?: refuse("the parser doesn't read a value for \"$VIEW_MODE_KEY\"")
    val value = (parsed[readAt] as OneRegisterInstruction).registerA
    val kept = parsed.filter {
        it.opcode == Opcode.IPUT_OBJECT && (it as TwoRegisterInstruction).registerA == value &&
            it.field()?.let { field -> field.definingClass == voice.type && field.type == STRING } == true
    }
    val viewMode = kept.singleOrNull()?.field() ?: refuse("expected the view mode kept in one field of ${voice.type}, found ${kept.size}")
    return VoiceReads(message, voice, media, source, code[addressAt], viewMode)
}

/**
 * The saver's entry: in the class keeping [MEDIA_SAVER] as its name, the static method that checks a
 * message's kind holds [SAVER_CHECK], and the one other static method taking the same parameters
 * calls it. Its parameters include the saver and a request whose one field of the message type
 * holds the message, and the saver has one activity.
 */
internal fun BytecodePatchContext.findVoiceSaver(message: String): VoiceSaver {
    val savers = classesHolding(MEDIA_SAVER, SAVER_CHECK)
    val saver = savers.singleOrNull() ?: refuse("expected one class holding \"$MEDIA_SAVER\" and \"$SAVER_CHECK\", found ${savers.size}")
    val statics = saver.methods.filter { AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" }
    val checkers = statics.filter { SAVER_CHECK in it.strings() }
    val checker = checkers.singleOrNull() ?: refuse("expected one check of a message's kind in ${saver.type}, found ${checkers.size}")
    val entries = statics.filter { method ->
        method !== checker && method.parameters() == checker.parameters() &&
            method.code().any { it.call()?.let { call -> call.definingClass == saver.type && call.name == checker.name && call.parameters() == checker.parameters() } == true }
    }
    val entry = entries.singleOrNull() ?: refuse("expected one entry calling ${saver.type}->${checker.name}, found ${entries.size}")
    val parameters = entry.parameters()
    val selves = parameters.indices.filter { parameters[it] == saver.type }
    val self = selves.singleOrNull() ?: refuse("the saver's entry takes ${selves.size} savers")
    val requests = parameters.indices.mapNotNull { index ->
        val type = classDefByOrNull(parameters[index]) ?: return@mapNotNull null
        type.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == message }.singleOrNull()?.let { index to it }
    }
    val (request, held) = requests.singleOrNull() ?: refuse("expected one request holding the message in the saver's entry, found ${requests.size}")
    val activities = saver.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == ACTIVITY }
    val activity = activities.singleOrNull() ?: refuse("expected one activity on ${saver.type}, found ${activities.size}")
    val mutable = mutableClassDefBy(saver.type).methods.single { it.name == entry.name && it.parameters() == parameters && it.returnType == "V" }
    val free = mutable.freeLocalsAt(VOICE_MESSAGE_PATCH, 0, 2)
    return VoiceSaver(mutable, self, request, held, activity, free)
}

/** Only called once [findVoiceMessages] found everything. */
internal fun BytecodePatchContext.applyVoiceMessages(found: VoiceMessages) {
    val offer = found.offer
    offer.method.addInstructions(
        offer.at,
        """
            invoke-static { v${offer.answer}, v${offer.message} }, $OFFER_VOICE
            move-result v${offer.answer}
        """,
    )

    val saver = found.saver
    val (held, activity) = saver.free
    saver.method.addInstructionsWithLabels(
        0,
        """
            iget-object v$held, ${saver.method.parameterRegister(saver.request)}, ${saver.message}
            iget-object v$activity, ${saver.method.parameterRegister(saver.saver)}, ${saver.activity}
            invoke-static { v$activity, v$held }, $SAVE_VOICE
            move-result v$held
            if-eqz v$held, :instagram
            return-void
        """,
        ExternalLabel("instagram", saver.method.getInstruction(0)),
    )

    val reads = found.reads
    val media = reads.media.call()!!
    val address = reads.address.call()!!
    replace(
        AUDIO_BRIDGE, 2,
        """
            const/4 v0, 0x0
            if-eqz p0, :none
            instance-of v1, p0, ${reads.message}
            if-eqz v1, :none
            check-cast p0, ${reads.message}
            iget-object v1, p0, ${reads.voice}
            if-eqz v1, :none
            ${invoke(reads.media)} { v1 }, $media
            move-result-object v1
            if-eqz v1, :none
            iget-object v1, v1, ${reads.source}
            if-eqz v1, :none
            ${invoke(reads.address)} { v1 }, $address
            move-result-object v0
            :none
            return-object v0
        """,
    )
    replace(
        VIEW_MODE_BRIDGE, 2,
        """
            const/4 v0, 0x0
            if-eqz p0, :none
            instance-of v1, p0, ${reads.message}
            if-eqz v1, :none
            check-cast p0, ${reads.message}
            iget-object v1, p0, ${reads.voice}
            if-eqz v1, :none
            iget-object v0, v1, ${reads.viewMode}
            :none
            return-object v0
        """,
    )
}

private fun invoke(call: Instruction) = if (call.opcode == Opcode.INVOKE_INTERFACE) "invoke-interface" else "invoke-virtual"

/** Throws unless the extension has both hooks and both bridges to fill. */
private fun BytecodePatchContext.stubs() {
    val extension = classDefByOrNull(VOICE_MESSAGE) ?: refuse("the extension has no $VOICE_MESSAGE")
    for (hook in listOf(OFFER_VOICE, SAVE_VOICE)) {
        if (extension.methods.none { it.signature() == hook && AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }) {
            refuse("the extension has no public static $hook")
        }
    }
    for (name in listOf(AUDIO_BRIDGE, VIEW_MODE_BRIDGE)) {
        extension.methods.singleOrNull {
            it.name == name && it.parameters() == listOf(OBJECT) && it.returnType == STRING && AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: refuse("$VOICE_MESSAGE has no static String $name(Object)")
    }
}

private fun BytecodePatchContext.replace(name: String, registers: Int, body: String) {
    val owner = mutableClassDefBy(VOICE_MESSAGE)
    val stub = owner.methods.single { it.name == name && it.parameters() == listOf(OBJECT) && it.returnType == STRING }
    val replacement = ImmutableMethod(stub.definingClass, stub.name, stub.parameters, stub.returnType,
        stub.accessFlags, stub.annotations, stub.hiddenApiRestrictions,
        ImmutableMethodImplementation(registers + 1, emptyList(), null, null)).toMutable().apply {
        addInstructionsWithLabels(0, body.trimIndent())
    }
    owner.methods.remove(stub)
    owner.methods.add(replacement)
}

/** A no-argument call on [type], virtual or through an interface. */
private fun Instruction.isGetter(type: String): Boolean {
    val call = call() ?: return false
    return (opcode == Opcode.INVOKE_VIRTUAL || opcode == Opcode.INVOKE_INTERFACE) && call.definingClass == type && call.parameterTypes.isEmpty()
}

private fun Instruction.setsLiteral(register: Int, value: Int): Boolean =
    (opcode == Opcode.CONST_4 || opcode == Opcode.CONST_16 || opcode == Opcode.CONST) &&
        (this as OneRegisterInstruction).registerA == register && (this as NarrowLiteralInstruction).narrowLiteral == value

private fun Method.signature() = "$definingClass->$name(${parameters().joinToString("")})$returnType"
private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Method.parameters(): List<String> = parameterTypes.map { it.toString() }
private fun Method.strings(): Set<String> = code().mapNotNull { (it.reference() as? StringReference)?.string }.toSet()
private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
private fun Instruction.call() = reference() as? MethodReference
private fun Instruction.field() = reference() as? FieldReference
private fun MethodReference.parameters(): List<String> = parameterTypes.map { it.toString() }
private fun Instruction.arguments(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}

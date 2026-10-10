/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.keep

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.FeedItemStandIns.instructions
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep in chat: the parser of a photo or video message's media hands the view mode it read to the
 * extension before storing it. Anything the patch can't tell apart fails it before an instruction changes.
 */
class KeepInChatHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(KEEP_IN_CHAT).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("viewMode is not in the extension: $declared", VIEW_MODE_HOOK.substringAfter("->") in declared)
        assertTrue("messageRead is not in the extension: $declared", MESSAGE_HOOK.substringAfter("->") in declared)
        for (stub in listOf("sentByYou(Ljava/lang/Object;)Z", "visualMedia(Ljava/lang/Object;)Ljava/lang/Object;",
            "itemMedia(Ljava/lang/Object;)Ljava/lang/Object;", "setViewMode(Ljava/lang/Object;Ljava/lang/String;)V")) {
            assertTrue("$stub is not in the extension: $declared", stub in declared)
        }
    }

    @Test
    fun theMessageCallsFollowTheThreeStoresAndTheStubsAreFilled() {
        val context = PatchContexts.of(standIns())
        val (parse, store) = context.findViewModeStore()
        val sent = context.findSentMessage(parse, store)
        keepViewModeInChat(parse, store)
        context.keepSentMediaAsSent(sent)
        assertMessageHooked("stand-in", context, MESSAGE_PARSER, sent.visual.toString(), sent.item.toString(), sent.sent.toString())
        assertStubs("stand-in", context, sent)
    }

    @Test
    fun aMessageItCantTellApartFailsThePatch() {
        refusesSent("found none", standIns(messages = emptyList()))
        refusesSent("Lfixture/OtherMessage", standIns(messages = listOf(messageParser(), messageParser(type = "Lfixture/OtherMessageParser;"))))
        refusesSent("doesn't load", standIns(messages = listOf(messageParser(sentKeyTwice = true))))
        refusesSent("no store after", standIns(messages = listOf(messageParser(sentStore = false))))
        refusesSent("isn't the media the media parser builds", standIns(messages = listOf(messageParser(visualType = "Lfixture/Other;"))))
        refusesSent("into one field", standIns(messages = listOf(messageParser(sameField = true))))
        refusesSent("isn't a flag", standIns(messages = listOf(messageParser(sentType = "I"))))
        refusesSent("another register", standIns(messages = listOf(messageParser(sentInto = 3))))
        refusesSent("which the message", standIns(messages = listOf(messageParser(sentOwner = "Lfixture/Unrelated;"))))
        refusesSent("written 2 times", standIns(messages = listOf(messageParser(writeAgain = true))))
    }

    @Test
    fun theViewModePassesThroughTheExtension() {
        val context = PatchContexts.of(listOf(parser()))
        val (parse, store) = context.findViewModeStore()
        keepViewModeInChat(parse, store)
        assertHooked("stand-in", context, PARSER)
    }

    @Test
    fun aMissingOrDoubledParserFailsThePatch() {
        refuses("found none", listOf(parser(keys = VISUAL_MEDIA_KEYS.drop(1))))
        refuses("Lfixture/OtherMedia", listOf(parser(), parser(type = "Lfixture/OtherMediaParser;")))
    }

    @Test
    fun aStoreItCantTellApartFailsThePatch() {
        refuses("doesn't load", listOf(parser(twice = true)))
        refuses("stores no view mode", listOf(parser(store = false)))
        refuses("isn't stored as text", listOf(parser(fieldType = "Ljava/lang/Object;")))
        refuses("not the media the parser builds", listOf(parser(fieldClass = "Lfixture/Other;")))
        refuses("2 times", listOf(parser(storeAgain = true)))
        refuses("jumps straight", listOf(parser(jump = true)))
    }

    /** In each declared build: the media parser's one view mode store, through the extension first. */
    @Test
    fun eachDeclaredBuildKeepsTheViewMode() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val context = PatchContexts.of(FixtureDex.classesHolding(bundle, "url_expire_at_secs"))
                val (parse, store) = context.findViewModeStore()
                keepViewModeInChat(parse, store)
                assertHooked(bundle.name, context, parse.definingClass)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** In each declared build: the message parser's three stores, a call after each, and the stubs filled from its fields. */
    @Test
    fun eachDeclaredBuildKeepsSentMediaAsSent() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        // The declared build and the other builds of the version, each compiled on its own.
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        run {
            for (bundle in bundles) {
                val kept = (FixtureDex.classesHolding(bundle, "url_expire_at_secs") + FixtureDex.classesHolding(bundle, "is_sent_by_viewer")).toMutableList()
                // The message class and its superclasses: the sent flag lives in one of them.
                var next: String? = kept.flatMap { it.methods }
                    .filter { m -> m.name == "unsafeParseFromJson" && m.implementation?.instructions?.any { i -> (i as? ReferenceInstruction)?.reference.let { r -> r is StringReference && r.string == "is_sent_by_viewer" } } == true }
                    .flatMap { it.implementation?.instructions?.toList().orEmpty() }
                    .firstOrNull { it.opcode == Opcode.NEW_INSTANCE }
                    ?.let { ((it as ReferenceInstruction).reference as TypeReference).type }
                while (next != null && next != "Ljava/lang/Object;") {
                    val found = FixtureDex.classes(bundle, setOf(next)).values.single()
                    kept += found
                    next = found.superclass
                }
                val context = PatchContexts.of(kept.distinctBy { it.type } + ExtensionDex.classDef(KEEP_IN_CHAT))
                val (parse, store) = context.findViewModeStore()
                val sent = context.findSentMessage(parse, store)
                keepViewModeInChat(parse, store)
                context.keepSentMediaAsSent(sent)
                assertMessageHooked(bundle.name, context, sent.parse.definingClass, sent.visual.toString(), sent.item.toString(), sent.sent.toString())
                assertStubs(bundle.name, context, sent)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The patch refuses for the reason given, and hooks no message. */
    private fun refusesSent(reason: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val refusal = assertThrows(PatchException::class.java) {
            val (parse, store) = context.findViewModeStore()
            val sent = context.findSentMessage(parse, store)
            keepViewModeInChat(parse, store)
            context.keepSentMediaAsSent(sent)
        }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        for (classDef in classes.filter { it.methods.any { m -> m.name == "parseMessage" } }) {
            val after = context.mutableClassDefBy(classDef.type).methods.flatMap { method -> method.instructions().map(::text) }
            assertTrue("${classDef.type} was hooked", after.none { it.contains("messageRead") })
        }
    }

    /** One call after each of the three stores, on the message's register, and no others. */
    private fun assertMessageHooked(what: String, context: BytecodePatchContext, parser: String, visual: String, item: String, sent: String) {
        val method = context.mutableClassDefBy(parser).methods.single { m ->
            m.instructions().any { (it as? ReferenceInstruction)?.reference?.toString() == MESSAGE_HOOK }
        }
        val code = method.instructions()
        val calls = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == MESSAGE_HOOK }
        assertEquals("$what: message calls", 3, calls.size)
        val stores = calls.map { (code[it - 1] as ReferenceInstruction).reference.toString() }
        assertEquals("$what: the stores the calls follow", setOf(visual, item, sent), stores.toSet())
        for (call in calls) {
            assertEquals("$what: the call", Opcode.INVOKE_STATIC, code[call].opcode)
            val store = code[call - 1] as TwoRegisterInstruction
            assertEquals("$what: the message register", store.registerB, (code[call] as FiveRegisterInstruction).registerC)
        }
    }

    /** The stubs read and write the fields the patch found. */
    private fun assertStubs(what: String, context: BytecodePatchContext, found: SentMessage) {
        val extension = context.mutableClassDefBy(KEEP_IN_CHAT)
        fun body(name: String) = extension.methods.single { it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) }.instructions()
        fun reads(name: String, opcode: Opcode, field: FieldReference) = body(name).any { it.opcode == opcode && (it as ReferenceInstruction).reference == field }
        assertTrue("$what: sentByYou", reads("sentByYou", Opcode.IGET_BOOLEAN, found.sent))
        assertTrue("$what: visualMedia", reads("visualMedia", Opcode.IGET_OBJECT, found.visual))
        assertTrue("$what: itemMedia", reads("itemMedia", Opcode.IGET_OBJECT, found.item))
        assertTrue("$what: setViewMode", reads("setViewMode", Opcode.IPUT_OBJECT, found.viewMode))
        assertEquals("$what: the message class", found.messageClass, (body("sentByYou").first { it.opcode == Opcode.CHECK_CAST } as ReferenceInstruction).reference.toString())
    }

    /** The patch refuses for the reason given, and nothing has changed. */
    private fun refuses(reason: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = classes.associate { it.type to it.methods.map { method -> method.instructions().map(::text) } }
        val refusal = assertThrows(PatchException::class.java) {
            val (parse, store) = context.findViewModeStore()
            keepViewModeInChat(parse, store)
        }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
        for (classDef in classes) {
            val after = context.mutableClassDefBy(classDef.type).methods.map { method -> method.instructions().map(::text) }
            assertEquals("${classDef.type} changed", before.getValue(classDef.type), after)
        }
    }

    /** The parser loads "view_mode", and its store of the mode follows the hook's call and answer on the same register. */
    private fun assertHooked(what: String, context: BytecodePatchContext, parser: String) {
        val calls = context.mutableClassDefBy(parser).methods.sumOf { method ->
            method.instructions().count { (it as? ReferenceInstruction)?.reference?.toString() == VIEW_MODE_HOOK }
        }
        assertEquals("$what: view mode calls in the parser", 1, calls)
        val code = context.mutableClassDefBy(parser).methods.single { method ->
            method.instructions().any { (it as? ReferenceInstruction)?.reference?.toString() == VIEW_MODE_HOOK }
        }.instructions()
        val call = code.indexOfFirst { (it as? ReferenceInstruction)?.reference?.toString() == VIEW_MODE_HOOK }
        val store = code[call + 2]
        assertTrue("$what: the hook comes after the key", code.take(call).any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == VIEW_MODE })
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[call + 1].opcode)
        assertEquals("$what: the store", Opcode.IPUT_OBJECT, store.opcode)
        assertEquals("$what: the store's type", "Ljava/lang/String;", ((store as ReferenceInstruction).reference as FieldReference).type)
        val mode = (store as TwoRegisterInstruction).registerA
        assertEquals("$what: the answer's register", mode, (code[call + 1] as OneRegisterInstruction).registerA)
    }

    private fun text(instruction: Instruction): String = when (val reference = (instruction as? ReferenceInstruction)?.reference) {
        null -> instruction.opcode.name
        is StringReference -> "\"${reference.string}\""
        else -> "${instruction.opcode.name} $reference"
    }

    private fun standIns(messages: List<ClassDef> = listOf(messageParser())): List<ClassDef> =
        listOf(parser(), messageClass("Lfixture/Message;", "Lfixture/MessageBase;"), messageClass("Lfixture/MessageBase;", "Ljava/lang/Object;"),
            ExtensionDex.classDef(KEEP_IN_CHAT)) + messages

    private fun messageClass(type: String, superclass: String): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, superclass, null, null, null, null, emptyList())

    private companion object {
        const val MESSAGE_PARSER = "Lfixture/MessageParser;"
        const val MESSAGE = "Lfixture/Message;"

        /**
         * Shaped like Instagram's message parser: builds the message into v2, loads its keys and stores the two
         * media and the sent flag into it, and returns it. The flag sits in the message's superclass, as 037A does.
         */
        fun messageParser(
            type: String = MESSAGE_PARSER,
            message: Int = 2,
            sentKeyTwice: Boolean = false,
            sentStore: Boolean = true,
            visualType: String = MEDIA,
            sameField: Boolean = false,
            sentType: String = "Z",
            sentInto: Int = message,
            sentOwner: String = "Lfixture/MessageBase;",
            writeAgain: Boolean = false,
        ): ClassDef {
            val visual = ImmutableFieldReference(MESSAGE, "visual", visualType)
            val item = if (sameField) visual else ImmutableFieldReference(MESSAGE, "item", MEDIA)
            val sent = ImmutableFieldReference(sentOwner, "sent", sentType)
            val code = mutableListOf<Instruction>(ImmutableInstruction21c(Opcode.NEW_INSTANCE, message, ImmutableTypeReference(MESSAGE)))
            if (writeAgain) code += ImmutableInstruction21c(Opcode.NEW_INSTANCE, message, ImmutableTypeReference(MESSAGE))
            code += ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("raven_media"))
            code += ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("visual_media"))
            code += ImmutableInstruction22c(Opcode.IPUT_OBJECT, 1, message, visual)
            code += ImmutableInstruction10t(Opcode.GOTO, 1)
            code += ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("message_item_dict"))
            code += ImmutableInstruction22c(Opcode.IPUT_OBJECT, 1, message, item)
            code += ImmutableInstruction10t(Opcode.GOTO, 1)
            code += ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("is_sent_by_viewer"))
            if (sentKeyTwice) code += ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("is_sent_by_viewer"))
            if (sentStore) code += ImmutableInstruction22c(Opcode.IPUT_BOOLEAN, 0, sentInto, sent)
            code += ImmutableInstruction10t(Opcode.GOTO, 1)
            code += ImmutableInstruction11x(Opcode.RETURN_OBJECT, message)
            val parse = ImmutableMethod(
                type, "parseMessage", listOf(ImmutableMethodParameter("Lfixture/Reader;", null, null)), "Ljava/lang/Object;",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, ImmutableMethodImplementation(maxOf(message, sentInto) + 2, code, null, null),
            )
            return ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(parse))
        }

        const val PARSER = "Lfixture/MediaParser;"
        const val MEDIA = "Lfixture/Media;"

        /** Shaped like Instagram's parser: builds the media, loads its keys, then reads the view mode into v0 and stores it. */
        fun parser(
            type: String = PARSER,
            keys: List<String> = VISUAL_MEDIA_KEYS,
            twice: Boolean = false,
            store: Boolean = true,
            fieldType: String = "Ljava/lang/String;",
            fieldClass: String = MEDIA,
            storeAgain: Boolean = false,
            jump: Boolean = false,
        ): ClassDef {
            val field = ImmutableFieldReference(fieldClass, "mode", fieldType)
            val code = mutableListOf<Instruction>(ImmutableInstruction21c(Opcode.NEW_INSTANCE, 2, ImmutableTypeReference(MEDIA)))
            for (key in keys.reversed() + (if (twice) listOf(VIEW_MODE) else emptyList())) {
                code += ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(key))
            }
            // A jump back to the store, from past the return, to show the patch won't put the hook where a jump skips it.
            code += ImmutableInstruction10x(Opcode.NOP)
            if (store) code += ImmutableInstruction22c(Opcode.IPUT_OBJECT, 0, 2, field)
            if (storeAgain) code += ImmutableInstruction22c(Opcode.IPUT_OBJECT, 0, 2, field)
            code += ImmutableInstruction11x(Opcode.RETURN_OBJECT, 2)
            if (jump) code += ImmutableInstruction10t(Opcode.GOTO, -3)
            val parse = ImmutableMethod(
                type, "parse", listOf(ImmutableMethodParameter("Lfixture/Reader;", null, null)), "Ljava/lang/Object;",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, ImmutableMethodImplementation(5, code, null, null),
            )
            return ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(parse))
        }
    }
}

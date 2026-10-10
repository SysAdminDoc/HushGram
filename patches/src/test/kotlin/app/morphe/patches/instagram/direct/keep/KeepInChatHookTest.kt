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
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
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
 * extension before storing it, the message parser hands the extension the message and its reader
 * after the stores that tell whether you sent it, and the media writer hands the view mode to the
 * extension on its way to the cache. Anything the patch can't tell apart fails it before an
 * instruction changes.
 */
class KeepInChatHookTest {
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(KEEP_IN_CHAT).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("viewMode is not in the extension: $declared", VIEW_MODE_HOOK.substringAfter("->") in declared)
        assertTrue("messageRead is not in the extension: $declared", MESSAGE_HOOK.substringAfter("->") in declared)
        assertTrue("storedViewMode is not in the extension: $declared", SAVE_HOOK.substringAfter("->") in declared)
        for (stub in listOf("sentByYou(Ljava/lang/Object;)Z", "senderId(Ljava/lang/Object;)Ljava/lang/String;",
            "viewerId(Ljava/lang/Object;)Ljava/lang/String;", "visualMedia(Ljava/lang/Object;)Ljava/lang/Object;",
            "itemMedia(Ljava/lang/Object;)Ljava/lang/Object;", "setViewMode(Ljava/lang/Object;Ljava/lang/String;)V")) {
            assertTrue("$stub is not in the extension: $declared", stub in declared)
        }
    }

    @Test
    fun theMessageCallsFollowTheFourStoresAndTheStubsAreFilled() {
        val context = PatchContexts.of(standIns())
        val (parse, store) = context.findViewModeStore()
        val sent = context.findSentMessage(parse, store)
        keepViewModeInChat(parse, store)
        context.keepSentMediaAsSent(sent)
        assertEquals("the reader register", 3, sent.reader)
        assertEquals("the reader class", READER, sent.readerClass)
        assertMessageHooked("stand-in", context, MESSAGE_PARSER, sent)
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
        refusesSent("another register", standIns(messages = listOf(messageParser(sentInto = 1))))
        refusesSent("which the message", standIns(messages = listOf(messageParser(sentOwner = "Lfixture/Unrelated;"))))
        refusesSent("written 2 times", standIns(messages = listOf(messageParser(writeAgain = true))))
    }

    /** The sender's user_id and the reader's account, which tell a message you sent without is_sent_by_viewer (#114). */
    @Test
    fun aSenderOrAccountItCantTellApartFailsThePatch() {
        refusesSent("no store after \"user_id\"", standIns(messages = listOf(messageParser(senderStore = false))))
        refusesSent("user_id isn't text", standIns(messages = listOf(messageParser(senderType = "Ljava/lang/Object;"))))
        refusesSent("never casts its reader", standIns(messages = listOf(messageParser(castReader = false))))
        refusesSent("the reader register is written 1 times", standIns(messages = listOf(messageParser(writeReader = true))))
        refusesSent("reads 0 accounts", standIns(messages = listOf(messageParser(sessions = 0))))
        refusesSent("reads 2 accounts", standIns(messages = listOf(messageParser(sessions = 2))))
        refusesSent("which the reader", standIns(messages = listOf(messageParser(sessionOwner = "Lfixture/Unrelated;"))))
        refusesSent("has no userId", standIns(userId = false))
    }

    /**
     * The extension remembers each media it changed by the object itself, in a WeakHashMap, so a
     * media class or superclass with its own equals or hashCode fails the patch, and so does one it
     * can't read. Overloads that aren't Object's are fine.
     */
    @Test
    fun aMediaThatComparesByMoreThanIdentityFailsThePatch() {
        val ownEquals = method("equals", listOf("Ljava/lang/Object;"), "Z")
        val ownHashCode = method("hashCode", emptyList(), "I")
        refusesSent("the media $MEDIA has its own hashCode in $MEDIA", standIns(media = listOf(media(MEDIA, methods = listOf(ownHashCode)))))
        refusesSent("has its own equals and hashCode in $MEDIA", standIns(media = listOf(media(MEDIA, methods = listOf(ownHashCode, ownEquals)))))
        refusesSent("has its own equals in Lfixture/MediaBase;", standIns(media = listOf(media(MEDIA, "Lfixture/MediaBase;"),
            media("Lfixture/MediaBase;", methods = listOf(ownEquals)))))
        refusesSent("can't read $MEDIA", standIns(media = emptyList()))
        refusesSent("can't read Lfixture/MediaBase;", standIns(media = listOf(media(MEDIA, "Lfixture/MediaBase;"))))

        val overloads = listOf(method("equals", listOf(MEDIA), "Z"), method("hashCode", listOf("I"), "I"))
        val context = PatchContexts.of(standIns(media = listOf(media(MEDIA, methods = overloads))))
        val (parse, store) = context.findViewModeStore()
        assertEquals("overloads aren't Object's", MEDIA, context.findSentMessage(parse, store).mediaClass)
    }

    @Test
    fun theViewModePassesThroughTheExtension() {
        val context = PatchContexts.of(listOf(parser()))
        val (parse, store) = context.findViewModeStore()
        keepViewModeInChat(parse, store)
        assertHooked("stand-in", context, PARSER)
    }

    @Test
    fun theViewModeOnItsWayToTheCachePassesThroughTheExtension() {
        val context = PatchContexts.of(listOf(parser()))
        val (parse, store) = context.findViewModeStore()
        val (writer, read) = context.findViewModeWrite(parse, store)
        keepViewModeInChat(parse, store)
        keepServerViewModeInCache(writer, read)
        assertEquals("the writer", "write", writer.name)
        assertSaved("stand-in", context, PARSER)
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

    @Test
    fun aWriterItCantTellApartFailsThePatch() {
        refusesWrite("has 0 methods writing out the view mode", listOf(parser(writers = 0)))
        refusesWrite("has 2 methods writing out the view mode", listOf(parser(writers = 2)))
        refusesWrite("more than once", listOf(parser(writerReadsTwice = true)))
        refusesWrite("doesn't write \"view_mode\"", listOf(parser(writerKey = false)))
    }

    /** In each declared build: the media parser's one view mode store, through the extension first, and its writer's one read, through it after. */
    @Test
    fun eachDeclaredBuildKeepsTheViewMode() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val context = PatchContexts.of(FixtureDex.classesHolding(bundle, "url_expire_at_secs"))
                val (parse, store) = context.findViewModeStore()
                val (writer, read) = context.findViewModeWrite(parse, store)
                keepViewModeInChat(parse, store)
                keepServerViewModeInCache(writer, read)
                assertHooked(bundle.name, context, parse.definingClass)
                assertSaved(bundle.name, context, parse.definingClass)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /**
     * In each declared build: the message parser's four stores, a call with the message and the
     * reader after each, the stubs filled from its fields, and the media writer's read through the
     * extension.
     */
    @Test
    fun eachDeclaredBuildKeepsSentMediaAsSent() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        // The declared build and the other builds of the version, each compiled on its own.
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        run {
            for (bundle in bundles) {
                val kept = (FixtureDex.classesHolding(bundle, "url_expire_at_secs") + FixtureDex.classesHolding(bundle, "is_sent_by_viewer") +
                    FixtureDex.classes(bundle, setOf(USER_SESSION)).values).toMutableList()
                fun holds(m: Method, key: String) =
                    m.implementation?.instructions?.any { i -> (i as? ReferenceInstruction)?.reference.let { r -> r is StringReference && r.string == key } } == true
                fun builtBy(parser: (Method) -> Boolean): String? = kept.flatMap { it.methods }.filter(parser)
                    .flatMap { it.implementation?.instructions?.toList().orEmpty() }
                    .firstOrNull { it.opcode == Opcode.NEW_INSTANCE }
                    ?.let { ((it as ReferenceInstruction).reference as TypeReference).type }
                // The message class and its superclasses: the sent flag and the sender live in one of them.
                // And the media class and its superclasses, which must keep Object's equals and hashCode.
                val message = builtBy { m -> m.name == "unsafeParseFromJson" && holds(m, "is_sent_by_viewer") }
                val media = builtBy { m ->
                    m.returnType == "Ljava/lang/Object;" && m.parameterTypes.size == 1 && VISUAL_MEDIA_KEYS.all { holds(m, it) }
                }
                assertTrue("${bundle.name}: the media class", media != null)
                for (built in listOf(message, media)) {
                    var next: String? = built
                    while (next != null && next != "Ljava/lang/Object;") {
                        val found = FixtureDex.classes(bundle, setOf(next)).values.single()
                        kept += found
                        next = found.superclass
                    }
                }
                val context = PatchContexts.of(kept.distinctBy { it.type } + ExtensionDex.classDef(KEEP_IN_CHAT))
                val (parse, store) = context.findViewModeStore()
                val sent = context.findSentMessage(parse, store)
                val (writer, read) = context.findViewModeWrite(parse, store)
                keepViewModeInChat(parse, store)
                context.keepSentMediaAsSent(sent)
                keepServerViewModeInCache(writer, read)
                assertMessageHooked(bundle.name, context, sent.parse.definingClass, sent)
                assertStubs(bundle.name, context, sent)
                assertSaved(bundle.name, context, parse.definingClass)
                assertEquals("${bundle.name}: the sender field", "Ljava/lang/String;", sent.sender.type)
                assertEquals("${bundle.name}: the account field", USER_SESSION, sent.session.type)
                assertEquals("${bundle.name}: the media class read", media, sent.mediaClass)
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

    /** One call after each of the four stores, on the message's register and the reader's, and no others. */
    private fun assertMessageHooked(what: String, context: BytecodePatchContext, parser: String, found: SentMessage) {
        val method = context.mutableClassDefBy(parser).methods.single { m ->
            m.instructions().any { (it as? ReferenceInstruction)?.reference?.toString() == MESSAGE_HOOK }
        }
        val code = method.instructions()
        val calls = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == MESSAGE_HOOK }
        assertEquals("$what: message calls", 4, calls.size)
        val stores = calls.map { (code[it - 1] as ReferenceInstruction).reference.toString() }
        assertEquals("$what: the stores the calls follow", setOf(found.visual, found.item, found.sent, found.sender).map { "$it" }.toSet(), stores.toSet())
        for (call in calls) {
            assertEquals("$what: the call", Opcode.INVOKE_STATIC, code[call].opcode)
            val store = code[call - 1] as TwoRegisterInstruction
            val invoke = code[call] as FiveRegisterInstruction
            assertEquals("$what: the call's registers", 2, invoke.registerCount)
            assertEquals("$what: the message register", store.registerB, invoke.registerC)
            assertEquals("$what: the reader register", found.reader, invoke.registerD)
        }
        assertEquals("$what: the reader is the last register", method.implementation!!.registerCount - 1, found.reader)
    }

    /** The stubs read and write the fields the patch found. */
    private fun assertStubs(what: String, context: BytecodePatchContext, found: SentMessage) {
        val extension = context.mutableClassDefBy(KEEP_IN_CHAT)
        fun body(name: String) = extension.methods.single { it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) }.instructions()
        fun reads(name: String, opcode: Opcode, field: String) = body(name).any { it.opcode == opcode && (it as ReferenceInstruction).reference.toString() == field }
        fun cast(name: String) = (body(name).first { it.opcode == Opcode.CHECK_CAST } as ReferenceInstruction).reference.toString()
        assertTrue("$what: sentByYou", reads("sentByYou", Opcode.IGET_BOOLEAN, "${found.sent}"))
        assertTrue("$what: senderId", reads("senderId", Opcode.IGET_OBJECT, "${found.sender}"))
        assertTrue("$what: viewerId's account", reads("viewerId", Opcode.IGET_OBJECT, "${found.session}"))
        assertTrue("$what: viewerId's user id", reads("viewerId", Opcode.IGET_OBJECT, USER_ID))
        assertTrue("$what: visualMedia", reads("visualMedia", Opcode.IGET_OBJECT, "${found.visual}"))
        assertTrue("$what: itemMedia", reads("itemMedia", Opcode.IGET_OBJECT, "${found.item}"))
        assertTrue("$what: setViewMode", body("setViewMode").any { it.opcode == Opcode.IPUT_OBJECT && (it as ReferenceInstruction).reference == found.viewMode })
        assertEquals("$what: the message class", found.messageClass, cast("sentByYou"))
        assertEquals("$what: the sender's message class", found.messageClass, cast("senderId"))
        assertEquals("$what: the reader class", found.readerClass, cast("viewerId"))
        // A reader with no account answers null on a path of its own, never through the user id read.
        val viewer = body("viewerId")
        val check = viewer.indexOfFirst { it.opcode == Opcode.IF_NEZ }
        assertTrue("$what: viewerId checks its account", check > 0)
        assertEquals("$what: viewerId's null", Opcode.CONST_4, viewer[check + 1].opcode)
        assertEquals("$what: viewerId's null return", Opcode.RETURN_OBJECT, viewer[check + 2].opcode)
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

    /** The patch refuses the media writer for the reason given, and nothing has changed. */
    private fun refusesWrite(reason: String, classes: List<ClassDef>) {
        val context = PatchContexts.of(classes)
        val before = classes.associate { it.type to it.methods.map { method -> method.instructions().map(::text) } }
        val refusal = assertThrows(PatchException::class.java) {
            val (parse, store) = context.findViewModeStore()
            val (writer, read) = context.findViewModeWrite(parse, store)
            keepViewModeInChat(parse, store)
            keepServerViewModeInCache(writer, read)
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

    /** The media writer's one read of the view mode is followed by the cache hook's call and answer on the read's registers, and nothing else calls it. */
    private fun assertSaved(what: String, context: BytecodePatchContext, parser: String) {
        val methods = context.mutableClassDefBy(parser).methods.filter { method ->
            method.instructions().any { (it as? ReferenceInstruction)?.reference?.toString() == SAVE_HOOK }
        }
        assertEquals("$what: methods calling the cache hook", 1, methods.size)
        val writer = methods.single()
        assertTrue("$what: the writer is static", AccessFlags.STATIC.isSet(writer.accessFlags))
        val code = writer.instructions()
        val calls = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == SAVE_HOOK }
        assertEquals("$what: cache hook calls", 1, calls.size)
        val call = calls.single()
        val read = code[call - 1]
        assertEquals("$what: the read", Opcode.IGET_OBJECT, read.opcode)
        assertEquals("$what: the read's type", "Ljava/lang/String;", ((read as ReferenceInstruction).reference as FieldReference).type)
        val invoke = code[call] as FiveRegisterInstruction
        assertEquals("$what: the mode", (read as TwoRegisterInstruction).registerA, invoke.registerC)
        assertEquals("$what: the media", read.registerB, invoke.registerD)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[call + 1].opcode)
        assertEquals("$what: the answer's register", read.registerA, (code[call + 1] as OneRegisterInstruction).registerA)
        assertTrue("$what: the key is written after", code.drop(call).any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == VIEW_MODE })
    }

    private fun text(instruction: Instruction): String = when (val reference = (instruction as? ReferenceInstruction)?.reference) {
        null -> instruction.opcode.name
        is StringReference -> "\"${reference.string}\""
        else -> "${instruction.opcode.name} $reference"
    }

    private fun standIns(
        messages: List<ClassDef> = listOf(messageParser()),
        userId: Boolean = true,
        media: List<ClassDef> = listOf(media(MEDIA)),
    ): List<ClassDef> =
        listOf(parser(), messageClass("Lfixture/Message;", "Lfixture/MessageBase;"), messageClass("Lfixture/MessageBase;", "Ljava/lang/Object;"),
            userSession(userId), ExtensionDex.classDef(KEEP_IN_CHAT)) + messages + media

    private fun messageClass(type: String, superclass: String): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, superclass, null, null, null, null, emptyList())

    /** The media the media parser builds, or a superclass of it, with the methods given. */
    private fun media(type: String, superclass: String = "Ljava/lang/Object;", methods: List<ImmutableMethod> = emptyList()): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, superclass, null, null, null, null,
            methods.map { ImmutableMethod(type, it.name, it.parameters, it.returnType, it.accessFlags, null, null, null) })

    /** A public abstract method, its class filled in by [media]. */
    private fun method(name: String, parameters: List<String>, returns: String): ImmutableMethod = ImmutableMethod(
        "Lfixture/Unset;", name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
        AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null,
    )

    /** Instagram's account class, with its userId or without. */
    private fun userSession(userId: Boolean): ClassDef = ImmutableClassDef(
        USER_SESSION, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null,
        if (userId) listOf(ImmutableField(USER_SESSION, "userId", "Ljava/lang/String;", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null))
        else emptyList(),
        emptyList(),
    )

    private companion object {
        const val MESSAGE_PARSER = "Lfixture/MessageParser;"
        const val MESSAGE = "Lfixture/Message;"
        const val READER = "Lfixture/SessionReader;"

        /**
         * Shaped like Instagram's message parser: casts its reader (the last register, v3) in place,
         * builds the message into v2, reads the account from the reader, loads its keys and stores the
         * two media, the sent flag and the sender into it, and returns it. The flag and the sender sit
         * in the message's superclass, as 032z does.
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
            senderStore: Boolean = true,
            senderType: String = "Ljava/lang/String;",
            castReader: Boolean = true,
            writeReader: Boolean = false,
            sessions: Int = 1,
            sessionOwner: String = READER,
        ): ClassDef {
            val reader = 3
            val visual = ImmutableFieldReference(MESSAGE, "visual", visualType)
            val item = if (sameField) visual else ImmutableFieldReference(MESSAGE, "item", MEDIA)
            val sent = ImmutableFieldReference(sentOwner, "sent", sentType)
            val sender = ImmutableFieldReference("Lfixture/MessageBase;", "sender", senderType)
            val code = mutableListOf<Instruction>()
            if (castReader) code += ImmutableInstruction21c(Opcode.CHECK_CAST, reader, ImmutableTypeReference(READER))
            code += ImmutableInstruction21c(Opcode.NEW_INSTANCE, message, ImmutableTypeReference(MESSAGE))
            if (writeAgain) code += ImmutableInstruction21c(Opcode.NEW_INSTANCE, message, ImmutableTypeReference(MESSAGE))
            for (i in 0 until sessions) {
                code += ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, reader,
                    ImmutableFieldReference(sessionOwner, "session$i", USER_SESSION))
            }
            if (writeReader) code += ImmutableInstruction21c(Opcode.CONST_STRING, reader, ImmutableStringReference("not a reader"))
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
            code += ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("user_id"))
            if (senderStore) code += ImmutableInstruction22c(Opcode.IPUT_OBJECT, 1, message, sender)
            code += ImmutableInstruction10t(Opcode.GOTO, 1)
            code += ImmutableInstruction11x(Opcode.RETURN_OBJECT, message)
            val parse = ImmutableMethod(
                type, "parseMessage", listOf(ImmutableMethodParameter("Lfixture/Reader;", null, null)), "Ljava/lang/Object;",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, ImmutableMethodImplementation(reader + 1, code, null, null),
            )
            return ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(parse))
        }

        const val PARSER = "Lfixture/MediaParser;"
        const val MEDIA = "Lfixture/Media;"

        /**
         * Shaped like Instagram's parser: builds the media, loads its keys, then reads the view mode into v0 and stores it.
         * Its class also holds the static writer, which reads the view mode into v0 and then writes the key.
         */
        fun parser(
            type: String = PARSER,
            keys: List<String> = VISUAL_MEDIA_KEYS,
            twice: Boolean = false,
            store: Boolean = true,
            fieldType: String = "Ljava/lang/String;",
            fieldClass: String = MEDIA,
            storeAgain: Boolean = false,
            jump: Boolean = false,
            writers: Int = 1,
            writerReadsTwice: Boolean = false,
            writerKey: Boolean = true,
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
            // The writer: v1 is the JSON writer, v2 the media.
            val written = ImmutableFieldReference(MEDIA, "mode", "Ljava/lang/String;")
            val writerMethods = (0 until writers).map { n ->
                val write = mutableListOf<Instruction>(ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 2, written))
                if (writerReadsTwice) write += ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 2, written)
                if (writerKey) write += ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference(VIEW_MODE))
                write += ImmutableInstruction10x(Opcode.RETURN_VOID)
                ImmutableMethod(
                    type, if (n == 0) "write" else "write$n",
                    listOf(ImmutableMethodParameter("Lfixture/Writer$n;", null, null), ImmutableMethodParameter(MEDIA, null, null)), "V",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, ImmutableMethodImplementation(3, write, null, null),
                )
            }
            return ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, listOf(parse) + writerMethods)
        }
    }
}

/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.reels

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.direct.history.MessageFields
import app.morphe.patches.instagram.direct.seen.visualCode
import app.morphe.patches.instagram.media.resume.PRODUCT_TYPE
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Don't open reels from chats (#810): the chat's opener asks the extension right after it looks the
 * tapped message up, and the bridges read what Instagram's own reel test there reads.
 */
class ChatReelsShutTest {
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val configs = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;"
    private val gates = "Lfixture/Gates;"
    private val opener = "Lfixture/Opener;"
    private val store = "Lfixture/Store;"
    private val wrapper = "Lfixture/Wrapper;"
    private val message = "Lfixture/Message;"
    private val cards = "Lfixture/Cards;"
    private val card = "Lfixture/Card;"
    private val links = "Lfixture/Links;"
    private val string = "Ljava/lang/String;"
    private val final = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
    private val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value

    @Test
    fun theHookAndBridgesAreInTheExtension() {
        val declared = ExtensionDex.classDef(CHAT_REELS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(MAY_OPEN.substringAfter("->"), "sharedProduct(Ljava/lang/Object;)$string", "shareLink(Ljava/lang/Object;)$string")) {
            assertTrue("$hook is not in the extension: $declared", hook in declared)
        }
    }

    /**
     * Past an earlier product type and link parse that aren't the reel test's, the opener asks with the
     * looked-up wrapper, a no returns before anything opens and a yes lands on Instagram's own next step.
     */
    @Test
    fun theOpenerAsksRightAfterItsLookupAndTheBridgesReadItsTest() {
        val context = context()
        val found = context.findChatReelOpener(context.findChatReels(), collector())
        assertEquals("$wrapper->message:$message", "${found.message}")
        assertEquals("$message->media:$MEDIA", "${found.media}")
        assertEquals("$MEDIA->productName()$string", "${found.product}")
        assertEquals("$message->cards()Lcom/google/common/collect/ImmutableList;", "${found.cards}")
        assertEquals("$cards->first(Ljava/util/List;)$card", "${found.firstCard}")
        assertEquals("$card->link:$string", "${found.link}")

        val stock = found.opener.implementation!!.instructions.toList()
        assertEquals("the lookup's answer comes just before", Opcode.MOVE_RESULT_OBJECT, stock[found.index - 1].opcode)
        assertEquals("Instagram's next step tests the wrapper", Opcode.IF_EQZ, stock[found.index].opcode)
        assertEquals("the wrapper is what the lookup answered", (stock[found.index - 1] as OneRegisterInstruction).registerA, found.wrapper)
        context.keepChatReelsShut(found)

        val code = found.opener.implementation!!.instructions.toList()
        assertEquals("added", stock.size + 4, code.size)
        assertEquals("asked once", 1, code.count { it.text() == MAY_OPEN })
        val asks = code[found.index] as RegisterRangeInstruction
        assertEquals("the hook", MAY_OPEN, code[found.index].text())
        assertEquals("handed the wrapper", listOf(found.wrapper), listOf(asks.startRegister))
        assertEquals("one register", 1, asks.registerCount)
        assertEquals("its answer", Opcode.MOVE_RESULT, code[found.index + 1].opcode)
        assertEquals("into the scratch", found.scratch, (code[found.index + 1] as OneRegisterInstruction).registerA)
        assertTrue("the scratch isn't the wrapper", found.scratch != found.wrapper)
        assertEquals("a yes goes on", Opcode.IF_NEZ, code[found.index + 2].opcode)
        assertEquals("a no stops", Opcode.RETURN_VOID, code[found.index + 3].opcode)
        assertEquals("Instagram's next step is kept", Opcode.IF_EQZ, code[found.index + 4].opcode)
        assertEquals("on the wrapper", found.wrapper, (code[found.index + 4] as OneRegisterInstruction).registerA)
        val yes = code[found.index + 2] as BuilderOffsetInstruction
        assertEquals("a yes lands on Instagram's next step", found.index + 4, yes.target.location.index)

        val bridges = context.mutableClassDefBy(CHAT_REELS).methods.associateBy { it.name }
        val product = bridges.getValue("sharedProduct").visualCode()
        assertEquals("the product bridge's cast", wrapper, product[0].text())
        assertEquals(listOf("${found.message}", "${found.media}", "${found.product}"),
            product.mapNotNull { it.text() }.drop(1))
        assertEquals("no message, no media", 2, product.count { it.opcode == Opcode.IF_EQZ })
        val link = bridges.getValue("shareLink").visualCode()
        assertEquals("the link bridge's cast", wrapper, link[0].text())
        assertEquals(listOf("${found.message}", "${found.cards}", "${found.firstCard}", "${found.link}"),
            link.mapNotNull { it.text() }.drop(1))
        assertEquals("no message, no cards, no first card", 3, link.count { it.opcode == Opcode.IF_EQZ })
        for (bridge in listOf(product, link)) {
            val none = bridge.indexOfFirst { it.opcode == Opcode.CONST_4 }
            assertEquals("a miss answers a fresh null", Opcode.RETURN_OBJECT, bridge[none + 1].opcode)
            assertEquals("and a hit answers what it read", Opcode.RETURN_OBJECT, bridge[none - 1].opcode)
        }
    }

    /**
     * A first card picked through a helper answering Object and cast to the card before its link is
     * read, as on 456.1's 64-bit builds, is still the reel test, and the link bridge casts it the same way.
     */
    @Test
    fun aFirstCardPickedAsObjectIsCastInTheLinkBridge() {
        val context = context(objectFirst = true)
        val found = context.findChatReelOpener(context.findChatReels(), collector())
        assertEquals("$cards->first(Ljava/util/List;)Ljava/lang/Object;", "${found.firstCard}")
        assertEquals("$card->link:$string", "${found.link}")
        context.keepChatReelsShut(found)

        val link = context.mutableClassDefBy(CHAT_REELS).methods.single { it.name == "shareLink" }.visualCode()
        assertEquals(listOf("${found.message}", "${found.cards}", "${found.firstCard}", card, "${found.link}"),
            link.mapNotNull { it.text() }.drop(1))
        val cast = link.indexOfFirst { it.opcode == Opcode.CHECK_CAST && it.text() == card }
        assertEquals("the cast follows the null check", Opcode.IF_EQZ, link[cast - 1].opcode)
        assertEquals("and comes before the link's read", Opcode.IGET_OBJECT, link[cast + 1].opcode)
    }

    @Test
    fun aBuildItCantReadFailsBeforeAnythingChanges() {
        val none = "expected one chat opener that looks a message up and tests it for a reel, found 0"
        val cases = listOf(
            context(lookup = false) to none,
            context(product = false) to none,
            context(link = false) to none,
            context(reels = false) to none,
            context(openers = 2) to "expected one chat opener that looks a message up and tests it for a reel, found 2",
            context(entered = true) to "something in $opener->open jumps to just past its message lookup",
            context(publicLink = false) to "$card->link can't be read from the extension",
            context(publicFirst = false) to "$cards->first can't be read from the extension",
            context(objectFirst = true, castFirst = false) to none,
        )
        for ((context, expected) in cases) {
            PatchContexts.use(context)
            val failure = assertThrows(expected, PatchException::class.java) { context.findChatReelOpener(context.findChatReels(), collector()) }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
        }
    }

    private fun Instruction.text(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    /** Only the collector's message field is read here: the wrapper's message, like 456's 05NJ.A10. */
    private fun collector(): ReelCollector {
        val base = "Lfixture/BaseMessage;"
        val field = { name: String, type: String -> ImmutableFieldReference(base, name, type) }
        return ReelCollector(
            MutableMethod(method("Lfixture/Collector;", "collect", listOf(wrapper), "V", 1, final, "return-void")),
            ImmutableFieldReference(wrapper, "message", message),
            MessageFields(base, field("id", string), field("time", string), field("sender", string), field("sent", "Z"), field("text", string), null),
            ImmutableMethodReference(wrapper, "reel", emptyList(), "Lfixture/Reel;"),
            ImmutableFieldReference("Lfixture/Reel;", "media", MEDIA),
            ImmutableFieldReference(wrapper, "session", session),
        )
    }

    /**
     * Shaped like 456's 0aoY.A01: a static opener handed the chat and the message asks the chat reels
     * test, looks the message's wrapper up by the message's id and later tests the wrapper's message for
     * a reel, by its media's product type, or with no media by its first share card's link next to
     * "reel" and "reels". Before that test it parses a type name and a link that aren't the message's.
     */
    private fun context(
        lookup: Boolean = true,
        product: Boolean = true,
        link: Boolean = true,
        reels: Boolean = true,
        openers: Int = 1,
        entered: Boolean = false,
        publicLink: Boolean = true,
        publicFirst: Boolean = true,
        objectFirst: Boolean = false,
        castFirst: Boolean = true,
    ) = PatchContexts.of(classes(lookup, product, link, reels, openers, entered, publicLink, publicFirst, objectFirst, castFirst) +
        ImmutableClassDef.of(ExtensionDex.classDef(CHAT_REELS)))

    /**
     * [objectFirst] makes the first card's pick answer Object, as 456.1's arm64-v8a and x86_64 builds'
     * shared helper does, and [castFirst] casts its answer to the card before the link is read.
     */
    private fun classes(
        lookup: Boolean,
        product: Boolean,
        link: Boolean,
        reels: Boolean,
        openers: Int,
        entered: Boolean,
        publicLink: Boolean,
        publicFirst: Boolean,
        objectFirst: Boolean,
        castFirst: Boolean,
    ): List<ClassDef> {
        val picked = if (objectFirst) "Ljava/lang/Object;" else card
        val test = method(
            gates, "chain", listOf(session), "Z", 3, final,
            """
                invoke-static {v4}, Lfixture/Configs;->of(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v2
                const-wide v0, 0x${CHAT_REELS_FLAG.toString(16)}L
                check-cast v2, $configs
                invoke-interface {v2, v0, v1}, $configs->Bae(J)Z
                move-result v0
                return v0
            """.trimIndent(),
        )
        val finds = if (lookup) "invoke-virtual {p2, v1}, $store->find($string)$wrapper" else "invoke-virtual {p2, v1}, $store->find($string)$message"
        val body = """
            const/4 v0, 0x0
            invoke-virtual {p3, v0}, $gates->chain($session)Z
            ${if (entered) "if-nez v0, :after" else "const/4 v3, 0x0"}
            iget-object v1, p1, $MESSAGE_ID->id:$string
            $finds
            move-result-object v0
            :after
            if-eqz v0, :out
            const-string v2, "clips"
            invoke-static {v2}, $PRODUCT_TYPE->A00($string)$PRODUCT_TYPE
            move-result-object v2
            const-string v2, "https://example.com"
            invoke-static {v2}, $links->parse($string)Landroid/net/Uri;
            move-result-object v2
            const-string v3, "reel"
            const-string v3, "reels"
            iget-object v1, v0, $wrapper->message:$message
            if-eqz v1, :out
            iget-object v2, v1, $message->media:$MEDIA
            if-eqz v2, :cards
            invoke-virtual {v2}, $MEDIA->productName()$string
            move-result-object v2
            if-eqz v2, :cards
            invoke-static {v2}, $PRODUCT_TYPE->A00($string)${if (product) PRODUCT_TYPE else "Ljava/lang/Object;"}
            move-result-object v2
            goto :out
            :cards
            invoke-virtual {v1}, $message->cards()Lcom/google/common/collect/ImmutableList;
            move-result-object v2
            if-eqz v2, :out
            invoke-static {v2}, $cards->first(Ljava/util/List;)$picked
            move-result-object v2
            ${if (objectFirst && castFirst) "check-cast v2, $card" else ""}
            if-eqz v2, :out
            iget-object v2, v2, $card->link:$string
            if-eqz v2, :out
            invoke-static {v2}, $links->parse($string)${if (link) "Landroid/net/Uri;" else "Ljava/lang/Object;"}
            move-result-object v2
            const-string v3, "reel"
            const-string v3, "${if (reels) "reels" else "shorts"}"
            :out
            return-void
        """.trimIndent()
        val opening = List(openers) { index ->
            method(opener, if (index == 0) "open" else "open$index", listOf(THREAD_KEY, MESSAGE_ID, store, gates), "V", 4, static, body)
        }
        val messageClass = ImmutableClassDef(
            message, final, "Ljava/lang/Object;", emptyList(), null, null,
            listOf(ImmutableField(message, "media", MEDIA, final, null, null, null)),
            listOf(method(message, "cards", emptyList(), "Lcom/google/common/collect/ImmutableList;", 1, final, "const/4 v0, 0x0\nreturn-object v0")),
        )
        val linkFlags = if (publicLink) final else AccessFlags.PRIVATE.value or AccessFlags.FINAL.value
        val firstFlags = if (publicFirst) static else AccessFlags.PRIVATE.value or AccessFlags.STATIC.value
        return listOf(
            classDef(gates, listOf(test)),
            classDef(opener, opening),
            classDef(wrapper, emptyList(), listOf(ImmutableField(wrapper, "message", message, final, null, null, null))),
            messageClass,
            classDef(MEDIA, listOf(method(MEDIA, "productName", emptyList(), string, 1, final, "const/4 v0, 0x0\nreturn-object v0"))),
            classDef(cards, listOf(method(cards, "first", listOf("Ljava/util/List;"), picked, 1, firstFlags, "const/4 v0, 0x0\nreturn-object v0"))),
            classDef(card, emptyList(), listOf(ImmutableField(card, "link", string, linkFlags, null, null, null))),
        )
    }

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method {
        val words = parameters.size + parameters.count { it == "J" || it == "D" }
        val total = registers + (if (AccessFlags.STATIC.isSet(flags)) 0 else 1) + words
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() })
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>, fields: List<ImmutableField> = emptyList()): ClassDef =
        ImmutableClassDef(type, final, "Ljava/lang/Object;", emptyList(), null, null, fields, methods)
}

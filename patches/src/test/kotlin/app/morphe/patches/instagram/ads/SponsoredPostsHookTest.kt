/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.ads

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.feed.FeedItemStandIns
import app.morphe.patches.instagram.feed.FeedItemStandIns.assertFilteredBeforeReturn
import app.morphe.patches.instagram.feed.FeedItemStandIns.instructions
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide ads' sponsored posts (S25, 450): the Older Posts page came with ads in its own response,
 * ordinary post items whose post carries Instagram's ad details, which the guarded insert never sees.
 * The feed item parse helper's answer goes through Ads.feedItem, and Ads.sponsored reads the item's
 * post field and the post's ad details getter. The same filter takes out the item kinds Instagram
 * gives only its rows of ads ([AD_UNITS]), which the patch checks the item's kind enum names. Checked
 * on stand-ins, on each declared build and on every other build of the version, with the insert's
 * guard written beside it.
 */
class SponsoredPostsHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(ADS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$FEED_ADS_FILTER is not in the extension: $declared", FEED_ADS_FILTER.substringAfter("->") in declared)
        assertTrue("the stub is not in the extension: $declared", "$SPONSORED_STUB(Ljava/lang/Object;)I" in declared)
    }

    /** The helper answers through the filter, and the stub reads the post field and its ad details. */
    @Test
    fun theStubReadsThePostsAdDetailsAndTheHelperAnswersThroughTheFilter() {
        val context = PatchContexts.of(feed() + media(Getter("A3C")) + ExtensionDex.classDef(ADS))

        context.findSponsoredPosts().write(context)

        val helper = context.mutableClassDefBy(FeedItemStandIns.ITEM).methods.single { it.name == "A02" }
        assertFilteredBeforeReturn("helper", helper, FEED_ADS_FILTER)
        assertEquals("helper size", 5 + 3, helper.instructions().size)
        assertReadsAdDetails("stand-in", stub(context), FeedItemStandIns.ITEM, "A0u", "A3C")
    }

    /** Media's boolean checks of the key answer no object, and its setter takes one: neither is the getter. */
    @Test
    fun onlyTheGetterOfTheKeyCounts() {
        val context = PatchContexts.of(
            feed() + media(Getter("AM5", returns = "Z"), Getter("ADo", parameters = listOf(DETAILS), returns = "V"), Getter("A3C"),
                Getter("A3D", hashed = false)) + ExtensionDex.classDef(ADS),
        )
        assertEquals("A3C", context.adDetailsGetter().name)
    }

    @Test
    fun aMediaWithoutTheGetterFailsThePatch() {
        refuses("found none") { PatchContexts.of(feed() + media(Getter("AM5", returns = "Z")) + ExtensionDex.classDef(ADS)).findSponsoredPosts() }
    }

    @Test
    fun twoGettersFailThePatch() {
        refuses("found A3C, A3D") { PatchContexts.of(feed() + media(Getter("A3C"), Getter("A3D")) + ExtensionDex.classDef(ADS)).findSponsoredPosts() }
    }

    @Test
    fun aGetterTheExtensionCantCallFailsThePatch() {
        refuses("isn't public") { PatchContexts.of(feed() + media(Getter("A3C", public = false)) + ExtensionDex.classDef(ADS)).findSponsoredPosts() }
        refuses("isn't public") { PatchContexts.of(feed() + media(Getter("A3C"), public = false) + ExtensionDex.classDef(ADS)).findSponsoredPosts() }
    }

    /**
     * The extension knows an ad pivot by its kind's name. An item whose kind enum doesn't name both
     * pivots, or two enums naming them, stops the patch before anything is written.
     */
    @Test
    fun anItemWhoseKindsDontNameBothAdPivotsFailsThePatch() {
        for (kinds in listOf(listOf("MEDIA", "AD", "CLIPS_NETEGO"), listOf("MEDIA", "AD", "CLIPS_NETEGO") + AD_UNITS.first())) {
            val context = PatchContexts.of(FeedItemStandIns.classes(kinds, postWrites = listOf("A0u")) + media(Getter("A3C")) + ExtensionDex.classDef(ADS))
            refuses("whose type names all of $AD_UNITS") { context.findSponsoredPosts() }
        }
        val twice = PatchContexts.of(
            FeedItemStandIns.classes(KINDS, fetchNames = listOf("COLD_START") + AD_UNITS, postWrites = listOf("A0u")) + media(Getter("A3C")) +
                ExtensionDex.classDef(ADS),
        )
        refuses("whose type names all of $AD_UNITS") { twice.findSponsoredPosts() }
    }

    /** An item keeping its post in two fields can't say which is the post an ad comes in. */
    @Test
    fun anItemWithTwoPostFieldsFailsThePatch() {
        val context = PatchContexts.of(FeedItemStandIns.classes(KINDS, postWrites = listOf("A0u", "A0v")) + media(Getter("A3C")) + ExtensionDex.classDef(ADS))
        refuses("keep it in one field") { context.findSponsoredPosts() }
    }

    /** Without the feed item parser, the insert's guard isn't written either: everything is found first. */
    @Test
    fun aBuildWithoutTheFeedItemLeavesTheInsertAsItWas() {
        val context = PatchContexts.of(listOf(injector(), media(Getter("A3C")), ExtensionDex.classDef(ADS)))
        assertThrows(PatchException::class.java) { context.hideAds() }
        assertEquals("the insert was guarded", Opcode.CONST_STRING, context.mutableClassDefBy(INJECTOR).methods.single().instructions().first().opcode)
    }

    /** Without the insert, the feed filter and the stub aren't written either. */
    @Test
    fun aBuildWithoutTheInsertLeavesTheFeedAsItWas() {
        val context = PatchContexts.of(feed() + media(Getter("A3C")) + ExtensionDex.classDef(ADS))
        val stubSize = stub(context).instructions().size
        assertThrows(PatchException::class.java) { context.hideAds() }
        assertEquals("the helper was filtered", 5, context.mutableClassDefBy(FeedItemStandIns.ITEM).methods.single { it.name == "A02" }.instructions().size)
        assertEquals("the stub was written", stubSize, stub(context).instructions().size)
    }

    /** In each declared build: the insert guarded, the helper filtered and the stub reading the post's ad details. */
    @Test
    fun eachDeclaredBuildTakesSponsoredPostsOut() {
        for (fixture in FeedItemStandIns.fixtures()) builds(fixture, fixture.bundle.name)
    }

    /** The same on every other build of the version, each compiled on its own. */
    @Test
    fun eachOtherBuildTakesSponsoredPostsOutToo() {
        val others = FeedItemStandIns.otherBuildFixtures()
        assertTrue("other builds of the declared version were not read", others.isNotEmpty())
        for (fixture in others) builds(fixture, fixture.bundle.parentFile.name)
    }

    private fun builds(fixture: FeedItemStandIns.Fixture, label: String) {
        val insert = FixtureDex.withStringPools(fixture.bundle, FixtureDex.classesLoading(fixture.bundle, AD_INJECTOR_STRINGS.first()))
        val context = PatchContexts.of((fixture.classes + insert).distinctBy { it.type } + ExtensionDex.classDef(ADS))
        val helperBefore = fixture.helper.instructions()

        context.hideAds()

        val found = context.findAdInjector()
        val guarded = context.mutableClassDefBy(found.type).methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }
        assertEquals("$label: the insert asks first", HIDE_ADS, guarded.instructions().first().referenceText())

        val helper = context.mutableClassDefBy(fixture.itemType).methods.single {
            it.name == fixture.helper.name && it.parameterTypes.map(Any::toString) == fixture.helper.parameterTypes.map(Any::toString)
        }
        val returns = helperBefore.count { it.opcode == Opcode.RETURN_OBJECT }
        assertEquals("$label: helper size", helperBefore.size + 3 * returns, helper.instructions().size)
        assertFilteredBeforeReturn(label, helper, FEED_ADS_FILTER)

        val code = stub(context).instructions()
        val field = code[1].referenceTo<FieldReference>()
        val getter = code[5].referenceTo<MethodReference>()
        assertReadsAdDetails(label, stub(context), fixture.itemType, field.name, getter.name)

        // The field is one the item's parser fills with a post, and the getter is Media's public one
        // loading the key's hash and answering an object.
        val parsers = fixture.classes.flatMap { it.methods }.filter { method ->
            method.returnType == "Ljava/lang/Object;" && method.parameterTypes.size == 1 && method.holds("media_or_ad") && method.holds("clips_netego")
        }
        val parser = parsers.singleOrNull() ?: error("$label: expected one feed item parser, found ${parsers.size}")
        val filled = parser.instructions().filter { it.opcode == Opcode.IPUT_OBJECT }.map { it.referenceTo<FieldReference>() }
            .filter { it.definingClass == fixture.itemType && it.type == MEDIA }.map { it.name }
        assertTrue("$label: the parser fills ${field.name} among $filled", field.name in filled)

        // The parser reads both pivots from a page, and one enum the item keeps its kind in names them.
        for (key in listOf("intent_aware_ad_pivot", "stand_alone_multi_ad_pivot")) assertTrue("$label: the parser reads $key", parser.holds(key))
        val naming = context.classDefBy(fixture.itemType).fields
            .filter { !AccessFlags.STATIC.isSet(it.accessFlags) }
            .mapNotNull { field -> fixture.classes.singleOrNull { it.type == field.type && it.superclass == "Ljava/lang/Enum;" } }
            .filter { enum -> AD_UNITS.all { unit -> enum.methods.any { it.name == "<clinit>" && it.holds(unit) } } }
        assertEquals("$label: enums naming the pivots", 1, naming.size)
        val media = context.mutableClassDefBy(MEDIA)
        val read = media.methods.single { it.name == getter.name && it.parameterTypes.isEmpty() && it.returnType == getter.returnType }
        assertTrue("$label: Media is public", AccessFlags.PUBLIC.isSet(media.accessFlags))
        assertTrue("$label: ${read.name} is public", AccessFlags.PUBLIC.isSet(read.accessFlags))
        assertTrue("$label: ${read.name} answers an object", read.returnType.startsWith("L"))
        assertTrue(
            "$label: ${read.name} loads the hash of $AD_DETAILS",
            read.instructions().any { it.opcode in CONSTS && (it as NarrowLiteralInstruction).narrowLiteral == AD_DETAILS.hashCode() },
        )
    }

    /**
     * The stub's first twelve instructions: the item cast, its post read, 0 for no post, the ad
     * details read, 0 for none and 1 for some, each with its own return.
     */
    private fun assertReadsAdDetails(what: String, stub: Method, itemType: String, field: String, getter: String) {
        val code = stub.instructions()
        assertEquals(
            "$what: the stub's opcodes",
            listOf(
                Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.IF_NEZ, Opcode.CONST_4, Opcode.RETURN,
                Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT, Opcode.IF_NEZ, Opcode.CONST_4, Opcode.RETURN,
                Opcode.CONST_4, Opcode.RETURN,
            ),
            code.take(12).map { it.opcode },
        )
        assertEquals("$what: the cast", itemType, code[0].referenceTo<TypeReference>().type)
        val post = code[1].referenceTo<FieldReference>()
        assertEquals("$what: the post field", "$itemType->$field:$MEDIA", "${post.definingClass}->${post.name}:${post.type}")
        val read = code[5].referenceTo<MethodReference>()
        assertEquals("$what: the getter's owner", MEDIA, read.definingClass)
        assertEquals("$what: the getter", getter, read.name)
        assertTrue("$what: the getter takes nothing", read.parameterTypes.isEmpty())
        assertEquals("$what: no post", 0, (code[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$what: no ad details", 0, (code[8] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$what: ad details", 1, (code[10] as NarrowLiteralInstruction).narrowLiteral)
        val written = stub.implementation!!.instructions.toList()
        assertEquals("$what: a post goes on to its details", 5, (written[2] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$what: details go to 1", 10, (written[7] as BuilderOffsetInstruction).target.location.index)
    }

    private fun stub(context: BytecodePatchContext): Method =
        context.mutableClassDefBy(ADS).methods.single { it.name == SPONSORED_STUB && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;") }

    /** The patch refuses, for the reason given, naming Hide ads. */
    private fun refuses(reason: String, search: () -> Unit) {
        val refusal = assertThrows(PatchException::class.java) { search() }
        val message = refusal.message.orEmpty()
        assertTrue("refused for another reason: $message", message.contains(reason))
        assertTrue("doesn't name Hide ads: $message", message.startsWith("$PATCH:"))
    }

    private inline fun <reified T> Instruction.referenceTo(): T = (this as ReferenceInstruction).reference as T

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Method.holds(string: String): Boolean =
        instructions().any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string }

    /** One of Media's methods, holding the hash of [AD_DETAILS] unless [hashed] is false. */
    private class Getter(
        val name: String,
        val returns: String = DETAILS,
        val parameters: List<String> = emptyList(),
        val public: Boolean = true,
        val hashed: Boolean = true,
    )

    private companion object {
        const val INJECTOR = "Lfixture/AdInjector;"
        const val DETAILS = "Lfixture/AdDetails;"
        val KINDS = listOf("MEDIA", "AD", "CLIPS_NETEGO") + AD_UNITS
        val CONSTS = setOf(Opcode.CONST, Opcode.CONST_16, Opcode.CONST_HIGH16, Opcode.CONST_4)

        fun feed(): List<ClassDef> = FeedItemStandIns.classes(KINDS, postWrites = listOf("A0u"))

        /** Media, public unless [public] is false, with [getters]. */
        fun media(vararg getters: Getter, public: Boolean = true): ClassDef = ImmutableClassDef(
            MEDIA, if (public) AccessFlags.PUBLIC.value else 0, "Ljava/lang/Object;", null, null, null, null,
            getters.map { getter ->
                val load = if (getter.hashed) listOf(ImmutableInstruction31i(Opcode.CONST, 0, AD_DETAILS.hashCode())) else emptyList()
                val end = when (getter.returns) {
                    "V" -> listOf(ImmutableInstruction10x(Opcode.RETURN_VOID))
                    "Z" -> listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN, 0))
                    else -> listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0))
                }
                ImmutableMethod(
                    MEDIA, getter.name, getter.parameters.map { ImmutableMethodParameter(it, null, null) }, getter.returns,
                    (if (getter.public) AccessFlags.PUBLIC.value else 0) or AccessFlags.FINAL.value, null, null,
                    ImmutableMethodImplementation(2 + getter.parameters.size, load + end, null, null),
                )
            },
        )

        /** Shaped like the insert Hide ads guards: static, three arguments, a boolean back, both strings loaded. */
        fun injector(): ClassDef = ImmutableClassDef(
            INJECTOR, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(
                ImmutableMethod(
                    INJECTOR, "A00", List(3) { ImmutableMethodParameter("Ljava/lang/Object;", null, null) }, "Z",
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                    ImmutableMethodImplementation(
                        8,
                        AD_INJECTOR_STRINGS.map { ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(it)) } +
                            listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 1), ImmutableInstruction11x(Opcode.RETURN, 0)),
                        null, null,
                    ),
                ),
            ),
        )
    }
}

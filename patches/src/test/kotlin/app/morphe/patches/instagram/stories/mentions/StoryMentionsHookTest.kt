/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.mentions

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.IMAGE_URL
import app.morphe.patches.instagram.download.INSTAGRAM_MEDIA
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.PROFILE_PICTURE_INFO
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.download.accountBridges
import app.morphe.patches.instagram.stories.time.STORY_ITEM
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * See who a story mentions: both story binders hand the page and its story to StoryMentions.bind
 * right after putting the story into the page, and the five stubs read the page's media view, the
 * story's Media, its mentions, each mention's account and the account's full name. A build where
 * any of it isn't there once fails the patch before anything changes.
 */
class StoryMentionsHookTest {
    private val page = "Lfixture/StoryPage;"
    private val mention = "Lfixture/MentionSticker;"
    private val shared = "Lfixture/MentionHolder;"
    private val otherShared = "Lfixture/MentionHolderToo;"
    private val mainBinder = "Lfixture/ReelBinder;"
    private val catchUpBinder = "Lfixture/CatchUpBinder;"
    private val public = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, flags: Int, registers: Int, vararg code: Instruction) =
        ImmutableMethod(
            owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
            ImmutableMethodImplementation(registers, code.toList(), null, null),
        )

    private fun field(owner: String, name: String, type: String, flags: Int = AccessFlags.PUBLIC.value) =
        ImmutableField(owner, name, type, flags, null, null, null)

    private fun type(
        owner: String, fields: List<ImmutableField> = emptyList(), methods: List<Method> = emptyList(),
        flags: Int = AccessFlags.PUBLIC.value, interfaces: List<String>? = null,
    ) = ImmutableClassDef(owner, flags, "Ljava/lang/Object;", interfaces, null, null, fields, methods)

    /** An interface of [name] declaring one abstract no-arg getter of the account. */
    private fun holder(name: String, getter: String, flags: Int) = type(
        name, methods = listOf(ImmutableMethod(name, getter, emptyList(), USER, AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null)),
        flags = flags,
    )

    /** A getter of [owner] answering [returns] that holds the hash of [field], then loads [classes] by const-class. */
    private fun getter(owner: String, name: String, returns: String, field: String, vararg classes: String, flags: Int = public) =
        method(
            owner, name, emptyList(), returns, flags, 2,
            ImmutableInstruction31i(Opcode.CONST, 0, field.hashCode()),
            *classes.map { ImmutableInstruction21c(Opcode.CONST_CLASS, 1, ImmutableTypeReference(it)) }.toTypedArray(),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        )

    /**
     * A binder loading the trace name, reading the story's Media and putting the story into the
     * page, as 450's do. In registers v0 and v1 its locals, then the page and the story.
     */
    private fun binder(owner: String, static: Boolean, puts: Int = 1): ClassDef {
        val pageRegister = if (static) 2 else 3
        val itemRegister = pageRegister + 1
        val code = listOf(
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(BIND_MEDIA)),
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 1, itemRegister, ImmutableFieldReference(STORY_ITEM, "A17", MEDIA)),
        ) + List(puts) {
            ImmutableInstruction22c(Opcode.IPUT_OBJECT, itemRegister, pageRegister, ImmutableFieldReference(page, "A03", STORY_ITEM))
        } + ImmutableInstruction10x(Opcode.RETURN_VOID)
        val flags = if (static) public or AccessFlags.STATIC.value else public
        return type(
            owner,
            methods = listOf(method(owner, if (static) "A03" else "A0U", listOf(page, STORY_ITEM), "V", flags, itemRegister + 1, *code.toTypedArray())),
        )
    }

    /** [closed] names the parts made package-private: item, media, mentions, mention, user or fullName. */
    private fun standIns(
        puts: Int = 1,
        mentionTypes: List<String> = listOf(mention),
        viewFlags: Int = AccessFlags.PUBLIC.value,
        binders: Boolean = true,
        closed: Set<String> = emptySet(),
        interfaces: List<String> = listOf(shared),
    ): List<ClassDef> {
        fun flags(part: String, open: Int) = if (part in closed) open and AccessFlags.PUBLIC.value.inv() else open
        return listOfNotNull(
            binder(mainBinder, static = true, puts = puts).takeIf { binders },
            binder(catchUpBinder, static = false).takeIf { binders },
            type(page, listOf(field(page, "A1Y", REEL_VIEW_GROUP, viewFlags), field(page, "A03", STORY_ITEM)), flags = public),
            type(STORY_ITEM, listOf(field(STORY_ITEM, "A17", MEDIA, public), field(STORY_ITEM, "A08", MEDIA)), flags = flags("item", public)),
            type(MEDIA, methods = listOf(
                getter(MEDIA, "A9U", "Ljava/util/List;", REEL_MENTIONS, *mentionTypes.toTypedArray(), flags = flags("mentions", public)),
                getter(MEDIA, "A9V", "Ljava/util/List;", "carousel_media"),
            ), flags = flags("media", public)),
            type(mention, methods = listOf(
                method(mention, "Dqh", emptyList(), USER, public, 1, ImmutableInstruction21c(Opcode.CONST_CLASS, 0, ImmutableTypeReference(USER)), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
                method(mention, "A00", emptyList(), USER, public or AccessFlags.STATIC.value, 1, ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)),
            ), flags = public, interfaces = interfaces),
            holder(shared, "Dqh", flags("holder", AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value)),
            holder(otherShared, "Dqi", AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value),
            type(USER, methods = listOf(
                getter(USER, "B0x", "Ljava/lang/String;", FULL_NAME, flags = flags("fullName", public)),
                getter(USER, "B0y", "Ljava/lang/String;", "username"),
            ), flags = flags("user", public)),
            ExtensionDex.classDef(STORY_MENTIONS),
        )
    }

    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(STORY_MENTIONS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})" }
        assertTrue("bind is not in the extension: $declared", BIND.substringAfter("->").substringBefore(")") + ")" in declared)
        for (stub in STUBS) assertTrue("$stub is not in the extension: $declared", "$stub(Ljava/lang/Object;)" in declared)
    }

    /** Both binders call bind right after the put, with the page and the story, and each stub reads its part. */
    @Test
    fun bothBindersHandThePageAndStoryOver() {
        val context = PatchContexts.of(standIns())

        val found = context.findStoryMentions()
        context.storyMentionStubs().fill(found)
        context.hookStoryBinds(found)

        assertEquals(2, found.binds.size)
        for (owner in listOf(mainBinder, catchUpBinder)) {
            val code = context.mutableClassDefBy(owner).methods.single().instructions()
            val put = code.indexOfFirst { it.opcode == Opcode.IPUT_OBJECT }
            val call = code[put + 1]
            assertEquals("$owner: the call after the put", BIND, ((call as ReferenceInstruction).reference as MethodReference).toString())
            val registers = code[put] as TwoRegisterInstruction
            assertEquals("$owner: the page", registers.registerB, (call as FiveRegisterInstruction).registerC)
            assertEquals("$owner: the story", registers.registerA, call.registerD)
            assertEquals("$owner: one call", 1, code.count { it.referenceText() == BIND })
        }

        assertEquals("$page->A1Y:$REEL_VIEW_GROUP", context.stub("itemView")[1].referenceText())
        assertEquals("$STORY_ITEM->A17:$MEDIA", context.stub("media")[1].referenceText())
        assertEquals("$MEDIA->A9U()Ljava/util/List;", context.stub("mentions")[1].referenceText())
        assertEquals("$shared->Dqh()$USER", context.stub("mentionUser")[1].referenceText())
        assertEquals(Opcode.INVOKE_INTERFACE, context.stub("mentionUser")[1].opcode)
        assertEquals(shared, ((context.stub("mentionUser")[0] as ReferenceInstruction).reference as TypeReference).type)
        assertEquals("$USER->B0x()Ljava/lang/String;", context.stub("fullName")[1].referenceText())
        for (name in STUBS) {
            val code = context.stub(name)
            assertEquals("$name casts first", Opcode.CHECK_CAST, code[0].opcode)
            assertTrue("$name returns what it read", code.take(4).any { it.opcode == Opcode.RETURN_OBJECT })
        }
    }

    @Test
    fun noBinderFailsThePatch() = refuses("no method loads") { PatchContexts.of(standIns(binders = false)).findStoryMentions() }

    @Test
    fun aBinderPuttingTheStoryTwiceFailsThePatch() = refuses("found 2") { PatchContexts.of(standIns(puts = 2)).findStoryMentions() }

    @Test
    fun mentionsOfTwoTypesFailThePatch() =
        refuses("one type of mention, found 2") { PatchContexts.of(standIns(mentionTypes = listOf(mention, USER))).findStoryMentions() }

    @Test
    fun aMentionWithNoSharedInterfaceFailsThePatch() =
        refuses("found 0") { PatchContexts.of(standIns(interfaces = emptyList())).findStoryMentions() }

    @Test
    fun aMentionWithTwoSharedInterfacesFailsThePatch() =
        refuses("found 2") { PatchContexts.of(standIns(interfaces = listOf(shared, otherShared))).findStoryMentions() }

    @Test
    fun aPrivateMediaViewFailsThePatch() =
        refuses("isn't public") { PatchContexts.of(standIns(viewFlags = AccessFlags.PRIVATE.value)).findStoryMentions() }

    /** Every type a stub casts to and every getter it calls is reached from the extension's package. */
    @Test
    fun whatTheStubsCantReachFailsThePatch() {
        for ((part, named) in mapOf(
            "item" to STORY_ITEM, "media" to MEDIA, "mentions" to "$MEDIA->A9U", "holder" to shared,
            "user" to USER, "fullName" to "$USER->B0x",
        )) {
            refuses("$named isn't public") { PatchContexts.of(standIns(closed = setOf(part))).findStoryMentions() }
        }
    }

    /** A refusal leaves every class as it was, since everything is found before anything changes. */
    @Test
    fun aRefusalChangesNothing() {
        val built = standIns(puts = 2)
        val context = PatchContexts.of(built)
        val before = built.associate { it.type to it.methods.sumOf { m -> m.instructions().size } }
        assertThrows(PatchException::class.java) { context.findStoryMentions() }
        val after = built.associate { c -> c.type to context.classDefBy(c.type).methods.sumOf { it.instructions().size } }
        assertEquals(before, after)
    }

    /**
     * In each declared build, the two binders holding the trace name are found and hooked once
     * each, the stubs are filled and the account's picture bridges are written.
     */
    @Test
    fun eachDeclaredBuildHooksBothBinders() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("the declared build and the six others", 7, bundles.size)
        run {
            for (bundle in bundles) {
                val binders = FixtureDex.classesHolding(bundle, BIND_MEDIA)
                val pages = binders.flatMap { it.methods }.flatMap { it.instructions() }
                    .filter { it.opcode == Opcode.IPUT_OBJECT }
                    .mapNotNull { ((it as ReferenceInstruction).reference as FieldReference).takeIf { f -> f.type == STORY_ITEM }?.definingClass }
                    .toSet()
                val models = FixtureDex.classes(bundle, pages + setOf(STORY_ITEM, MEDIA, USER, PROFILE_PICTURE_INFO, IMAGE_URL))
                val key = REEL_MENTIONS.hashCode()
                val mentionTypes = models.getValue(MEDIA).methods
                    .filter { method -> method.instructions().any { (it as? NarrowLiteralInstruction)?.narrowLiteral == key } }
                    .flatMap { it.instructions() }
                    .filter { it.opcode == Opcode.CONST_CLASS }
                    .map { ((it as ReferenceInstruction).reference as TypeReference).type }
                    .toSet()
                val mentionClasses = FixtureDex.classes(bundle, mentionTypes)
                val holders = FixtureDex.classes(bundle, mentionClasses.values.flatMap { it.interfaces }.toSet())
                val classes = (binders + models.values + mentionClasses.values + holders.values +
                    ExtensionDex.classDef(STORY_MENTIONS) + ExtensionDex.classDef(INSTAGRAM_MEDIA)).distinctBy { it.type }
                val context = PatchContexts.of(classes)

                val found = context.findStoryMentions()
                val stubs = context.storyMentionStubs()
                val pictures = context.accountBridges(PATCH)
                val cast = { context.mutableClassDefBy(INSTAGRAM_MEDIA).methods
                    .filter { it.instructions().firstOrNull()?.opcode == Opcode.CHECK_CAST }.map { it.name }.toSet() }
                val castBefore = cast()
                stubs.fill(found)
                pictures()
                context.hookStoryBinds(found)

                assertEquals("${buildOf(bundle)}: the binders", 2, found.binds.size)
                for (bind in found.binds) {
                    val code = context.mutableClassDefBy(bind.type).methods.single {
                        it.name == bind.name && it.parameterTypes.map(Any::toString) == bind.parameters
                    }.instructions()
                    assertEquals("${buildOf(bundle)}: ${bind.type}->${bind.name} calls bind once", 1, code.count { it.referenceText() == BIND })
                }
                for (name in STUBS) assertEquals("${buildOf(bundle)}: $name", Opcode.CHECK_CAST, context.stub(name)[0].opcode)
                val user = context.stub("mentionUser")
                assertEquals("${buildOf(bundle)}: the account is read through an interface", Opcode.INVOKE_INTERFACE, user[1].opcode)
                assertEquals("${buildOf(bundle)}: the cast", found.mention, ((user[0] as ReferenceInstruction).reference as TypeReference).type)
                val holder = holders.getValue(found.mention)
                assertTrue("${buildOf(bundle)}: ${found.mention} is public", AccessFlags.PUBLIC.isSet(holder.accessFlags))
                assertTrue("${buildOf(bundle)}: ${found.mention} is an interface", AccessFlags.INTERFACE.isSet(holder.accessFlags))
                val getters = holder.methods.filter {
                    it.parameterTypes.isEmpty() && it.returnType == USER && AccessFlags.ABSTRACT.isSet(it.accessFlags)
                }
                assertEquals("${buildOf(bundle)}: the interface's one getter of the account", listOf(found.mentionUser), getters.map { it.name })
                assertTrue("${buildOf(bundle)}: the named mention class implements it", mentionClasses.values.any { found.mention in it.interfaces })
                // The plain model Instagram's JSON parser fills the cached list with implements it too:
                // another public class taking a User in its constructor.
                val implementers = mutableSetOf<String>()
                val jsonModels = mutableSetOf<String>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) if (found.mention in classDef.interfaces) {
                        implementers += classDef.type
                        if (AccessFlags.PUBLIC.isSet(classDef.accessFlags) && classDef.type !in mentionClasses &&
                            classDef.methods.any { it.name == "<init>" && it.parameterTypes.any { type -> type == USER } }
                        ) jsonModels += classDef.type
                    }
                }
                assertTrue("${buildOf(bundle)}: no other public class taking a User implements ${found.mention}: $implementers", jsonModels.isNotEmpty())
                assertTrue("${buildOf(bundle)}: ${found.mention} is implemented by ${implementers}", true)
                println("MENTION-INTERFACE ${bundle.name} ${found.mention}->${found.mentionUser} json=$jsonModels named=${mentionClasses.keys.filter { found.mention in mentionClasses.getValue(it).interfaces }}")
                // The account's username and picture, and nothing only a profile's own patches read.
                assertEquals("${buildOf(bundle)}: the bridges written", setOf("profilePicture", "username", "candidateUrl"), cast() - castBefore)
                checked++
            }
        }
        assertTrue("no fixture was checked", checked > 0)
    }

    private fun name(bundle: java.io.File) = bundle.parentFile?.name ?: bundle.name

    private fun buildOf(bundle: java.io.File) = bundle.parentFile?.name ?: bundle.name

    private fun refuses(detail: String, run: () -> Unit) {
        val failure = assertThrows(PatchException::class.java) { run() }
        assertTrue(failure.message, failure.message!!.contains(detail))
    }

    private fun app.morphe.patcher.patch.BytecodePatchContext.stub(name: String): List<Instruction> =
        mutableClassDefBy(STORY_MENTIONS).methods.single { it.name == name }.instructions()

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.let {
        when (it) {
            is MethodReference -> "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
            is FieldReference -> "${it.definingClass}->${it.name}:${it.type}"
            else -> it.toString()
        }
    }
}

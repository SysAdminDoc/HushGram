/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.speed

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.media.taptoplay.REEL_VIEWER_FRAGMENT
import app.morphe.patches.instagram.media.taptoplay.VIDEO_PLAYER_FIELD
import app.morphe.patches.instagram.stories.mentions.BIND
import app.morphe.patches.instagram.stories.mentions.BIND_MEDIA
import app.morphe.patches.instagram.stories.mentions.findStoryPages
import app.morphe.patches.instagram.stories.time.STORY_ITEM
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StorySpeedHookTest {
    /** The calls the patch writes are in the StorySpeed the bundle ships, public and static. */
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(STORY_SPEED).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        listOf(SPEED_PLAYING, SPEED_BOUND).forEach { assertTrue("$it is not in the extension: $declared", it.substringAfter("->") in declared) }
    }

    /**
     * On 454.0.0.54.77's arm64 build the patch finds the player's speed setter (HOC on 0NuF) and the
     * viewer's callback for a story's video starting (GaD), puts the hook in front of its return,
     * fills every stub and tells the extension after each binder's put.
     */
    @Test
    fun theDeclaredBuildFindsTheSetterAndTheCallback() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val sites = builds(bundle, bundle.name)
                if (version == "454.0.0.54.77") {
                    assertEquals("${bundle.name}: the player", "LX/0NuF;", sites.playerInterface)
                    assertEquals("${bundle.name}: the speed setter", "HOC", sites.setter)
                    assertEquals("${bundle.name}: the callback", "GaD", sites.playing)
                    assertEquals("${bundle.name}: the binders", 2, sites.pages.binds.size)
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildFindsThemToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    /** A second run finds its own calls and refuses instead of telling the extension twice. */
    @Test
    fun aSecondRunRefuses() {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val context = PatchContexts.of(classes(bundle))
        context.hookStorySpeed(context.findStorySpeed())
        val failure = assertThrows(PatchException::class.java) { context.findStorySpeed() }
        assertTrue(failure.message, failure.message!!.contains("already calls $STORY_SPEED"))
    }

    /**
     * See who a story mentions hooks the same puts. Whichever patch runs first, each binder calls both
     * hooks once, and the bind hook still sits right after the put or right after the other call.
     */
    @Test
    fun itStacksWithSeeWhoAStoryMentions() {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val context = PatchContexts.of(classes(bundle))
        // See who a story mentions first, the way its hookStoryBinds writes its call.
        for (bind in context.findStoryPages("test").binds) {
            context.mutableClassDefBy(bind.type).methods.single { it.name == bind.name && it.parameters() == bind.parameters }
                .addInstructions(bind.index + 1, "invoke-static { v${bind.page}, v${bind.item} }, $BIND")
        }
        val found = context.findStorySpeed()
        context.hookStorySpeed(found)
        for (bind in found.pages.binds) {
            val code = context.mutableClassDefBy(bind.type).methods
                .single { it.name == bind.name && it.parameters() == bind.parameters }.code()
            assertEquals("${bind.type}->${bind.name} calls bind once", 1, code.count { it.reference() == BIND })
            val at = code.indexOfFirst { it.reference() == SPEED_BOUND }
            assertEquals("${bind.type}->${bind.name} calls $SPEED_BOUND once", 1, code.count { it.reference() == SPEED_BOUND })
            assertTrue("${bind.type}->${bind.name}'s put is right before the speed's call", code[at - 1].putsStory())
            assertEquals("${bind.type}->${bind.name}'s other call follows", BIND, code[at + 1].reference())
        }
        // And the other way round: See who a story mentions finds the same binders after this patch.
        assertEquals(found.pages.binds.size, context.findStoryPages("test").binds.size)
    }

    /** A build the patch can't read fails before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val all = classes(bundle)
        val playerType = all.single { it.type == REEL_VIEWER_FRAGMENT }.fields.single { it.name == VIDEO_PLAYER_FIELD }.type
        // The binders and the string pools some of them ask for the name.
        val binders = FixtureDex.classesLoading(bundle, BIND_MEDIA).map { it.type }.toSet()
        val cases = listOf(
            all.filter { playerType !in it.interfaces } to "nothing implements $playerType",
            all.filter { it.type != STORY_SPEED_READER } to "the extension has no $STORY_SPEED_READER",
            all.filter { it.type != REEL_VIEWER_FRAGMENT } to "this build has no $REEL_VIEWER_FRAGMENT",
            all.filter { it.type !in binders } to "no method loads $BIND_MEDIA",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val before = classes.associate { c -> c.type to c.methods.sumOf { it.code().size } }
            val failure = assertThrows(expected, PatchException::class.java) { context.findStorySpeed() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val after = classes.associate { c -> c.type to context.classDefBy(c.type).methods.sumOf { it.code().size } }
            assertEquals("$expected: something was written", before, after)
        }
    }

    private fun builds(bundle: File, label: String): StorySpeedSites {
        val classes = classes(bundle)
        val context = PatchContexts.of(classes)
        val sites = context.findStorySpeed()
        context.hookStorySpeed(sites)

        val viewer = classes.single { it.type == REEL_VIEWER_FRAGMENT }
        val stockCallback = viewer.methods.single { it.name == sites.playing && it.parameters() == sites.playingParameters }
        val stock = stockCallback.code().filter { it.opcode != Opcode.NOP }
        val hookedMethod = context.mutableClassDefBy(REEL_VIEWER_FRAGMENT).methods
            .single { it.name == sites.playing && it.parameters() == sites.playingParameters }
        val hooked = hookedMethod.code().filter { it.opcode != Opcode.NOP }
        val returns = stock.count { it.opcode == Opcode.RETURN_VOID }
        assertTrue("$label: ${sites.playing} returns", returns > 0)
        assertEquals("$label: ${sites.playing} grows by one call per return", stock.size + returns, hooked.size)
        val calls = hooked.indices.filter { hooked[it].reference() == SPEED_PLAYING }
        assertEquals("$label: one call per return", returns, calls.size)
        val self = hookedMethod.implementation!!.registerCount - 1 - sites.playingParameters.sumOf { if (it == "J" || it == "D") 2 else 1 }
        for (at in calls) {
            assertEquals("$label: the call is right before a return", Opcode.RETURN_VOID, hooked[at + 1].opcode)
            val call = hooked[at] as RegisterRangeInstruction
            assertEquals("$label: the call reads this, the story and the page", self, call.startRegister)
            assertEquals("$label: three registers", 3, call.registerCount)
        }
        // Every branch that went to a return now goes to the call in front of it.
        val all = hookedMethod.implementation!!.instructions.toList()
        for (instruction in all.filterIsInstance<BuilderOffsetInstruction>()) {
            val target = all[instruction.target.location.index]
            assertTrue("$label: a branch skips the hook to land on a return", target.opcode != Opcode.RETURN_VOID)
        }

        val reader = context.mutableClassDefBy(STORY_SPEED_READER)
        fun stub(name: String) = reader.methods.single { it.name == name }.code()
        assertEquals("$label: setSpeed is filled", Opcode.CHECK_CAST, stub("setSpeed").first().opcode)
        assertEquals("$label: isPage is filled", Opcode.INSTANCE_OF, stub("isPage").first().opcode)
        assertEquals("$label: pageView is filled", Opcode.CHECK_CAST, stub("pageView").first().opcode)
        assertEquals("$label: ad is filled", Opcode.CHECK_CAST, stub("ad").first().opcode)
        assertEquals("$label: the speed goes to the player interface", "${sites.playerInterface}->${sites.setter}(F)V",
            stub("setSpeed").mapNotNull { it.method() }.single().toString())
        assertEquals("$label: isPage checks the page", sites.pages.page,
            ((stub("isPage").first() as ReferenceInstruction).reference as TypeReference).type)

        for (bind in sites.pages.binds) {
            val code = context.mutableClassDefBy(bind.type).methods
                .single { it.name == bind.name && it.parameters() == bind.parameters }.code()
            val at = code.indexOfFirst { it.reference() == SPEED_BOUND }
            assertEquals("$label: ${bind.type}->${bind.name} calls $SPEED_BOUND once", 1, code.count { it.reference() == SPEED_BOUND })
            assertTrue("$label: ${bind.type}->${bind.name} calls it right after the put", code[at - 1].putsStory())
        }
        return sites
    }

    /**
     * The viewer, its player interface and what implements it, the story viewer's binders with their
     * string pools, their page, the story item and Media, and the extension's stubs.
     */
    private fun classes(bundle: File): List<ClassDef> {
        val models = FixtureDex.classes(bundle, setOf(REEL_VIEWER_FRAGMENT, STORY_ITEM, MEDIA))
        val viewer = models[REEL_VIEWER_FRAGMENT] ?: error("${bundle.name} has no $REEL_VIEWER_FRAGMENT")
        val player = viewer.fields.single { it.name == VIDEO_PLAYER_FIELD }.type
        val players = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.type == player || player in classDef.interfaces) players += ImmutableClassDef.of(classDef)
            }
        }
        val binders = FixtureDex.classesLoading(bundle, BIND_MEDIA)
        val pages = binders.flatMap { it.methods }.flatMap { it.code() }.filter { it.putsStory() }
            .mapNotNull { it.field()?.definingClass }.toSet()
        val pageClasses = FixtureDex.classes(bundle, pages)
        return (listOf(viewer) + models.values + players + binders + pageClasses.values +
            ExtensionDex.classDef(STORY_SPEED_READER) + ExtensionDex.classDef(STORY_SPEED)).distinctBy { it.type }
    }

    private fun Method.parameters(): List<String> = parameterTypes.map(Any::toString)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.method(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

    private fun Instruction.putsStory(): Boolean = opcode == Opcode.IPUT_OBJECT && field()?.type == STORY_ITEM

    private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.let {
        when (it) {
            is MethodReference -> "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
            is FieldReference -> "${it.definingClass}->${it.name}:${it.type}"
            else -> it.toString()
        }
    }
}

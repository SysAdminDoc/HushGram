/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.seen

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.stories.autoadvance.STORY_VIEWER
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The lists the reel store hands out for the tray (Home's top story row and the stories unit inside
 * the feed) go through StoryTrayOrder just before the store's methods return, after they let go of
 * the store, and nothing else of the store changes (#241).
 */
class StoryTrayOrderHookTest {
    /** The hook the patch calls and the stub it fills are in the extension the bundle ships, static. */
    @Test
    fun theHookAndTheStubAreInTheExtension() {
        val methods = ExtensionDex.classDef(STORY_TRAY_ORDER).methods
        val hook = methods.single { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == TRAY_ORDER.substringAfter("->") }
        assertTrue("the hook isn't public and static", AccessFlags.PUBLIC.isSet(hook.accessFlags) && AccessFlags.STATIC.isSet(hook.accessFlags))
        val stub = methods.single { it.name == "seen" }
        assertEquals(listOf(OBJECT, OBJECT), stub.parameterTypes.map(Any::toString))
        assertEquals("I", stub.returnType)
        assertTrue("the stub isn't static", AccessFlags.STATIC.isSet(stub.accessFlags))
    }

    /**
     * On each declared build the store's list for Home's top story row and its list for the feed's
     * stories unit are found alone, each hooked right before its one return, whose monitor-exit stays
     * ahead of the hook; the stub asks the reel's own check.
     */
    @Test
    fun eachDeclaredBuildOrdersTheTray() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        var builds = 0
        for (version in versions) {
            val fixtures = Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } +
                Fixtures.otherBuilds().filter { it.parentFile.name.contains("-$version-") }
            for (bundle in fixtures) {
                val classes = trayClasses(bundle)
                val context = PatchContexts.of(classes)
                val rings = context.findStoryRings()
                val found = context.findStoryTray(rings.reelClass)
                assertEquals("${bundle.name}: the reel", rings.reelClass, found.reelClass)
                assertEquals("${bundle.name}: the top row's list, then the feed unit's", listOf(listOf("Z", "Z"), listOf(LIST)), found.sites.map { it.parameters })

                val store = classes.single { it.type == found.store }
                val befores = found.sites.map { site ->
                    store.methods.single { it.name == site.lister && it.parameterTypes.map(Any::toString) == site.parameters }
                }
                val top = befores.first()
                assertTrue("${bundle.name}: the top row's list isn't synchronized", AccessFlags.DECLARED_SYNCHRONIZED.isSet(top.accessFlags))
                assertTrue("${bundle.name}: the top row's list doesn't log an empty store", top.code().any { it.referenceText() == MAIN_TRAY_EMPTY })
                for ((site, before) in found.sites.zip(befores.map { it.code() })) {
                    assertEquals("${bundle.name} ${site.lister}: the monitor-exit", Opcode.MONITOR_EXIT, before[site.at - 1].opcode)
                    assertEquals("${bundle.name} ${site.lister}: the one return", 1, before.count { it.opcode == Opcode.RETURN_OBJECT })
                }

                context.orderStoryTray(found)

                fun shape(code: List<Instruction>) = code.filter { it.opcode != Opcode.NOP }.map { it.opcode to it.referenceText() }
                for ((site, before) in found.sites.zip(befores.map { it.code() })) {
                    val at = "${bundle.name} ${site.lister}"
                    val after = context.mutableClassDefBy(found.store).methods.single {
                        it.name == site.lister && it.parameterTypes.map(Any::toString) == site.parameters
                    }.code()
                    val hooked = after.indexOfFirst { it.referenceText() == TRAY_ORDER }
                    assertTrue("$at: the hook isn't in the list's method", hooked > 0)
                    assertEquals("$at: the hook sits after the monitor-exit", Opcode.MONITOR_EXIT, after[hooked - 1].opcode)
                    assertEquals("$at: the answer is taken back", Opcode.MOVE_RESULT_OBJECT, after[hooked + 1].opcode)
                    assertEquals("$at: the answer goes to the register returned", site.result, (after[hooked + 1] as OneRegisterInstruction).registerA)
                    assertEquals("$at: the answer is returned", Opcode.RETURN_OBJECT, after[hooked + 2].opcode)
                    assertEquals("$at: the hook's registers", listOf(site.result, site.self), (after[hooked] as FiveRegisterInstruction).registers())
                    assertEquals("$at: the rest of the method", shape(before), shape(after).filterIndexed { i, _ -> i !in hooked..hooked + 1 })
                }
                assertEquals("${bundle.name}: one call of the hook per list", 2, context.calls(TRAY_ORDER))

                val bridge = context.mutableClassDefBy(STORY_TRAY_ORDER).methods.single { it.name == "seen" }
                val stub = bridge.code()
                assertTrue("${bundle.name}: the bridge needs three locals beside its parameters", bridge.implementation!!.registerCount >= 5)
                val references = stub.mapNotNull { it.referenceText() }
                for (required in listOf("${found.store}->${found.sessionField}:$USER_SESSION", found.identity.kind.toString(),
                        found.identity.owner.toString(), found.identity.userId.toString(), found.identity.sessionId.toString(),
                        found.identity.reelId.toString(), "Ljava/lang/Enum;->name()Ljava/lang/String;", "USER_REEL", "add_to_story")) {
                    assertTrue("${bundle.name}: the identity bridge omits $required", required in references)
                }
                val nativeSeen = stub.indexOfFirst { it.referenceText() == "${found.reelClass}->${found.seenCheck}(${USER_SESSION})Z" }
                assertTrue("${bundle.name}: native seen must follow every identity read", nativeSeen > 0 &&
                    listOf(found.identity.owner, found.identity.userId, found.identity.sessionId, found.identity.reelId).all { read ->
                        stub.indexOfFirst { it.referenceText() == read.toString() } in 0 until nativeSeen
                    })
                assertEquals("${bundle.name}: native seen is read once", 1, stub.count { it.referenceText() == "${found.reelClass}->${found.seenCheck}(${USER_SESSION})Z" })

                checked += version
                builds++
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
        assertEquals("a retained build has no fixture", Fixtures.BUILDS, builds)
    }

    /** A build whose reel store isn't there is refused, saying so. */
    @Test
    fun aBuildWithoutTheStoreFails() {
        for (version in AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val classes = trayClasses(bundle)
                val trimmed = PatchContexts.of(classes.filter { it.type != storeOf(classes) })
                val refusal = assertThrows(PatchException::class.java) { trimmed.findStoryTray(trimmed.findStoryRings().reelClass) }
                assertTrue(refusal.message, "found 0" in refusal.message!!)
            }
        }
    }

    /**
     * A store without the list for Home's top story row is refused rather than patched with only
     * the feed unit's list ordered, which left the top row in the server's order (#241).
     */
    @Test
    fun aStoreWithoutTheTopRowsListFails() {
        for (version in AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val classes = trayClasses(bundle)
                val store = storeOf(classes)
                val trimmed = PatchContexts.of(classes.map { classDef ->
                    if (classDef.type != store) return@map classDef
                    ImmutableClassDef(
                        classDef.type, classDef.accessFlags, classDef.superclass, classDef.interfaces, classDef.sourceFile,
                        classDef.annotations, classDef.fields,
                        classDef.methods.filterNot { method -> method.code().any { it.referenceText() == MAIN_TRAY_EMPTY } },
                    )
                })
                val refusal = assertThrows(PatchException::class.java) { trimmed.findStoryTray(trimmed.findStoryRings().reelClass) }
                assertTrue(refusal.message, "top story row" in refusal.message!! && "found 0" in refusal.message!!)
            }
        }
    }

    @Test
    fun missingIdentityReadersRefuseBeforeAnyListIsHooked() {
        val bundle = Fixtures.files { it.extension == "apks" }.first()
        val classes = trayClasses(bundle)
        val original = PatchContexts.of(classes)
        val found = original.findStoryTray(original.findStoryRings().reelClass)
        val missing = mutableListOf(classes.filter { it.type != found.identity.kind.type })
        for (reader in listOf(found.identity.owner, found.identity.userId, found.identity.sessionId, found.identity.reelId)) {
            missing += classes.map { classDef ->
                if (classDef.type != reader.definingClass) classDef else ImmutableClassDef(
                    classDef.type, classDef.accessFlags, classDef.superclass, classDef.interfaces, classDef.sourceFile,
                    classDef.annotations, classDef.fields, classDef.methods.filterNot { it.name == reader.name },
                )
            }
        }
        for (trimmed in missing) {
            val context = PatchContexts.of(trimmed)
            assertThrows(PatchException::class.java) { context.findStoryTray(context.findStoryRings().reelClass) }
            assertEquals("an identity refusal must not install a partial hook", 0, context.calls(TRAY_ORDER))
        }
    }

    private fun storeOf(classes: List<ClassDef>): String {
        val context = PatchContexts.of(classes)
        val reel = context.findStoryRings().reelClass
        return context.findStoryTray(reel).store
    }

    /**
     * The classes the finders read on [bundle]: those the ring finder reads, the reel store (the
     * class holding the list method the tray finder looks for) and the extension's classes.
     */
    private fun trayClasses(bundle: File): List<ClassDef> {
        val stores = FixtureDex.classesHolding(bundle, LOCAL_SEEN_STORE)
        val state = stores.flatMap { it.methods }.single {
            it.name == "<init>" && it.parameterTypes.size == 2 && it.parameterTypes[0].toString() == USER_SESSION
        }.parameterTypes[1].toString()
        val stateClass = FixtureDex.classes(bundle, setOf(state)).getValue(state)
        val stateWrite = stateClass.methods.single {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.map(Any::toString) == listOf(state, STRING, "J")
        }.key()
        val stateWriters = callersOf(bundle, stateWrite)
        val reelWrite = stateWriters.single { it.definingClass != state && stores.none { store -> store.type == it.definingClass } }.key()
        val reelWriters = callersOf(bundle, reelWrite)
        val reel = reelWrite.substringBefore("->")
        val trayStores = FixtureDex.methodsWhere(bundle, { true }) { method -> isListMethod(method, reel) }.map { it.definingClass }.toSet()
        val identityTypes = FixtureDex.classes(bundle, setOf(reel)).getValue(reel).fields
            .map { it.type }.filter { it.startsWith("L") } + listOf("Lcom/instagram/user/model/User;", USER_SESSION)
        val types = (stores.map { it.type } + state + stateWriters.map { it.definingClass } + reelWriters.map { it.definingClass } + trayStores + identityTypes + STORY_VIEWER).toSet()
        return (
            FixtureDex.withStringPools(bundle, FixtureDex.classes(bundle, types).values) +
                ExtensionDex.classDef(STORY_SEEN_RINGS) + ExtensionDex.classDef(STORY_TRAY_ORDER)
            ).map { ImmutableClassDef.of(it) }.distinctBy { it.type }
    }

    /** A cheap look for the store's list method: public, a list in and out, with a monitor and a call of the reel's seen check somewhere in its class. */
    private fun isListMethod(method: Method, reel: String): Boolean {
        if (AccessFlags.STATIC.isSet(method.accessFlags) || !AccessFlags.PUBLIC.isSet(method.accessFlags)) return false
        if (method.returnType != "Ljava/util/List;" || method.parameterTypes.map(Any::toString) != listOf("Ljava/util/List;")) return false
        val code = method.code()
        if (code.none { it.opcode == Opcode.MONITOR_ENTER }) return false
        return code.any {
            val call = (it as? ReferenceInstruction)?.reference as? MethodReference
            it.opcode == Opcode.INVOKE_DIRECT && call?.definingClass == method.definingClass && call.returnType == "Ljava/util/ArrayList;"
        } && reel.isNotEmpty()
    }

    private fun callersOf(bundle: File, key: String): List<Method> =
        FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.toString() == key } }) { method ->
            method.code().any { it.referenceText() == key }
        }


    /** Calls in Instagram's own classes (the extension's are left out). */
    private fun app.morphe.patcher.patch.BytecodePatchContext.calls(reference: String): Int {
        var count = 0
        classDefForEach { classDef ->
            if (!classDef.type.startsWith("Lapp/hushgram/")) classDef.methods.forEach { method -> count += method.code().count { it.referenceText() == reference } }
        }
        return count
    }

    private fun FiveRegisterInstruction.registers(): List<Int> =
        listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)

    private fun Method.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = when (val reference = (this as? ReferenceInstruction)?.reference) {
        is StringReference -> reference.string
        else -> reference?.toString()
    }

    private companion object {
        const val OBJECT = "Ljava/lang/Object;"
        const val STRING = "Ljava/lang/String;"
        const val LIST = "Ljava/util/List;"
        const val MAIN_TRAY_EMPTY = "main feed tray reels is empty"
    }
}

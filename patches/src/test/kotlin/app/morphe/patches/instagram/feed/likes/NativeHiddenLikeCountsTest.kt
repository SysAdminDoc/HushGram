/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.likes

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.feed.likes.ShowHiddenLikeCountsHookTest.Companion.assertCountHook
import app.morphe.patches.instagram.feed.likes.ShowHiddenLikeCountsHookTest.Companion.assertDecisionHook
import app.morphe.patches.instagram.feed.likes.ShowHiddenLikeCountsHookTest.Companion.assertRowHook
import app.morphe.patches.instagram.feed.likes.ShowHiddenLikeCountsHookTest.Companion.assertStub
import app.morphe.patches.instagram.feed.likes.ShowHiddenLikeCountsHookTest.Companion.code
import app.morphe.patches.instagram.feed.likes.ShowHiddenLikeCountsHookTest.Companion.reference
import app.morphe.patches.instagram.feed.likes.ShowHiddenLikeCountsHookTest.Companion.snapshot
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both hooks on each of 450's seven builds: the one method every like row asks, which tests the
 * flag first and answers hidden at once, and the one like count reader behind the post model,
 * which keeps the count it read for a null test right after.
 */
class NativeHiddenLikeCountsTest {
    @Test fun everyBuildHandsTheFlagAndTheCountThroughTheHooks() {
        val checked = mutableListOf<String>()
        for (bundle in bundles()) {
            val name = bundle.absolutePath
            val classes = nativeClasses(bundle)
            val context = PatchContexts.of(classes.values + ExtensionDex.classDef(HIDDEN_LIKE_COUNTS))
            val anchors = context.findHiddenLikeCounts()

            assertEquals(name, DECIDE, anchors.decider.parameterTypes.map(Any::toString))
            assertEquals(name, "Z", anchors.decider.returnType)
            assertEquals("$name: the flag is the decider's last register", anchors.decider.implementation!!.registerCount - 1, anchors.flag)
            assertEquals("$name: the poster's id is the register before it", anchors.flag - 1, anchors.poster)
            assertEquals("$name: the decider's second parameter is the id", "Ljava/lang/String;", anchors.decider.parameterTypes[1].toString())
            assertEquals("$name: the poster is read with the interface the flag is", anchors.treeFlagRead.definingClass,
                anchors.childRead.definingClass)
            assertEquals("$name: a poster's data is the same interface", anchors.treeFlagRead.definingClass, anchors.childRead.returnType)
            assertEquals("$name: the id is read off the same interface", anchors.treeFlagRead.definingClass, anchors.textRead.definingClass)
            assertEquals("$name: the id is a string", "Ljava/lang/String;", anchors.textRead.returnType)
            assertTrue("$name: the reader is static", AccessFlags.STATIC.isSet(anchors.counter.accessFlags))
            assertEquals(name, "I", anchors.counter.returnType)
            assertEquals(name, 1, anchors.counter.parameterTypes.size)
            val countRead = anchors.counter.code()[anchors.countAt - 1].methodReference()
            assertEquals("$name: the stub reads with the interface the count is read with", countRead.definingClass,
                anchors.treeFlagRead.definingClass)

            assertTrue("$name: every like row is found, ${anchors.rows.size}", anchors.rows.size >= 3)
            assertEquals("$name: the count is read with the interface the flag is", anchors.treeFlagRead.definingClass,
                anchors.countRead.definingClass)
            for (row in anchors.rows) {
                val rowCode = row.method.code()
                assertEquals("$name: ${row.method} keeps the flag", Opcode.MOVE_RESULT_OBJECT, rowCode[row.at].opcode)
                assertEquals("$name: ${row.method} reads the flag the way the reader does", anchors.treeFlagRead.toString(),
                    rowCode[row.at - 1].methodReference().toString())
            }

            val rowMethods = anchors.rows.map { it.method.toString() }.toSet()
            val hooked = setOf(anchors.decider.toString(), anchors.counter.toString()) + rowMethods
            val decider = anchors.decider.code()
            val counter = anchors.counter.code()
            val originalRows = anchors.rows.associate { it.method.toString() to it.method.code() }
            val before = classes.values.associate { it.type to snapshot(it.methods.filter { method -> method.toString() !in hooked }) }
            context.applyHiddenLikeCounts(anchors)

            assertDecisionHook(context.mutableClassDefBy(anchors.decider.definingClass).methods.single { it.toString() == anchors.decider.toString() }, decider)
            assertCountHook(context.mutableClassDefBy(anchors.counter.definingClass).methods.single { it.toString() == anchors.counter.toString() },
                counter, anchors.countAt, anchors.tree, anchors.count)
            assertStub(context.mutableClassDefBy(HIDDEN_LIKE_COUNTS).methods.single { it.name == TREE_FLAG_STUB },
                anchors.treeFlagRead.toString())
            assertStub(context.mutableClassDefBy(HIDDEN_LIKE_COUNTS).methods.single { it.name == TREE_COUNT_STUB },
                anchors.countRead.toString())
            assertStub(context.mutableClassDefBy(HIDDEN_LIKE_COUNTS).methods.single { it.name == TREE_CHILD_STUB },
                anchors.childRead.toString())
            assertStub(context.mutableClassDefBy(HIDDEN_LIKE_COUNTS).methods.single { it.name == TREE_TEXT_STUB },
                anchors.textRead.toString())
            for ((type, rows) in anchors.rows.groupBy { it.method.definingClass }) {
                for (method in context.mutableClassDefBy(type).methods.filter { it.toString() in rowMethods }) {
                    val here = rows.filter { it.method.toString() == method.toString() }.sortedBy { it.at }
                    val hookCode = method.code()
                    assertEquals("$name: $method hooked once per read", here.size, hookCode.count { it.reference() == ROW_READ })
                    if (here.size == 1) assertRowHook(method, originalRows.getValue(method.toString()), here[0].at, here[0].tree, here[0].flag)
                }
            }
            for (type in classes.keys) {
                assertEquals("$name: $type changed past the two hooks", before[type],
                    snapshot(context.mutableClassDefBy(type).methods.filter { it.toString() !in hooked }))
            }
            checked += name
        }
        for (code in BUILDS) assertTrue("no fixture of 450 build $code among $checked", checked.any { it.contains("-$code") })
    }

    @Test fun dexBackedInstructionReReadsFindTheSameAnchors() {
        for (bundle in bundles().take(1)) {
            val copied = PatchContexts.of(nativeClasses(bundle).values + ExtensionDex.classDef(HIDDEN_LIKE_COUNTS)).findHiddenLikeCounts()
            val context = PatchContexts.of(FixtureDex.classesAsRead(bundle, nativeClasses(bundle).keys).values +
                ExtensionDex.classDef(HIDDEN_LIKE_COUNTS))
            val anchors = context.findHiddenLikeCounts()
            assertEquals(copied.decider.toString(), anchors.decider.toString())
            assertEquals(copied.counter.toString(), anchors.counter.toString())
            assertEquals(listOf(copied.poster, copied.flag, copied.countAt, copied.tree, copied.count),
                listOf(anchors.poster, anchors.flag, anchors.countAt, anchors.tree, anchors.count))
            assertEquals(listOf(copied.childRead.toString(), copied.textRead.toString()),
                listOf(anchors.childRead.toString(), anchors.textRead.toString()))
            val decider = anchors.decider.code()
            val counter = anchors.counter.code()
            assertEquals(copied.rows.map { it.method.toString() to it.at }, anchors.rows.map { it.method.toString() to it.at })
            context.applyHiddenLikeCounts(anchors)
            assertDecisionHook(context.mutableClassDefBy(anchors.decider.definingClass).methods.single { it.toString() == anchors.decider.toString() }, decider)
            assertCountHook(context.mutableClassDefBy(anchors.counter.definingClass).methods.single { it.toString() == anchors.counter.toString() },
                counter, anchors.countAt, anchors.tree, anchors.count)
        }
    }

    private companion object {
        val BUILDS = listOf(385611395, 385611400, 385611404, 385611431, 385611438, 385611439, 385611440)
        const val SESSION = "Lcom/instagram/common/session/UserSession;"
        val DECIDE = listOf(SESSION, "Ljava/lang/String;", "Z")
        val CONSTS = setOf(Opcode.CONST, Opcode.CONST_16, Opcode.CONST_HIGH16, Opcode.CONST_4)
        val cached = mutableMapOf<String, Map<String, ClassDef>>()

        fun bundles(): List<File> {
            val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
            return versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
                Fixtures.otherBuilds()
        }

        /**
         * The post model, every class loading either key, and the classes of every method taking the
         * session, an id and a boolean that those call: what the patch reads, and the decider among them.
         */
        fun nativeClasses(bundle: File): Map<String, ClassDef> = cached.getOrPut(bundle.absolutePath) {
            val found = mutableMapOf<String, ClassDef>()
            val asked = mutableSetOf<String>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    if (classDef.type in found) continue
                    if (classDef.type != POST_MODEL && classDef.methods.none { it.loadsAKey() }) continue
                    found[classDef.type] = ImmutableClassDef.of(classDef)
                    for (method in classDef.methods) {
                        for (instruction in method.code()) {
                            val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: continue
                            if (called.returnType == "Z" && called.parameterTypes.map(Any::toString) == DECIDE) asked += called.definingClass
                        }
                    }
                }
            }
            found + FixtureDex.classes(bundle, asked - found.keys)
        }

        fun Method.loadsAKey(): Boolean = code().any {
            it.opcode in CONSTS && (it as NarrowLiteralInstruction).narrowLiteral.let { value -> value == LIKES_HIDDEN_KEY || value == LIKE_COUNT_KEY }
        }

        fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.methodReference(): MethodReference =
            (this as ReferenceInstruction).reference as MethodReference
    }
}

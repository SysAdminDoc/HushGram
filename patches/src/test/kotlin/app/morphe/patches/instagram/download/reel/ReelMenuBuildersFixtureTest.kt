/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.reel

import app.morphe.Fixtures
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which methods Download any reel takes for builders of the reel menu, on every build of the
 * declared version. The feed menu's builder also adds Download after the download check, and it's
 * Download any video's to change: a reel's hooks there would offer Instagram's own Download row on
 * every post of yours, photos included. On 456's x86 build Redex merged the feed menu's builder into
 * one method with a lambda of the reel menu, which touches the reel menu's helper in another arm.
 */
class ReelMenuBuildersFixtureTest {
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val list = "Ljava/util/ArrayList;"

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.method(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.calls(method: Method): Boolean = method().let {
        it != null && it.definingClass == method.definingClass && it.name == method.name && it.returnType == method.returnType &&
            it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
    }

    /** The one method loading [string], and its class's methods. */
    private fun loader(bundle: java.io.File, string: String): Method {
        val classes = FixtureDex.classesLoading(bundle, string).associateBy { it.type }
        return classes.values.flatMap { holder -> holder.methods.filter { FixtureDex.loads(it, string, classes) } }.single()
    }

    /** The feed menu's builder adds Download with a static method of its state that takes the option and the list. */
    private fun Method.addsToTheFeedMenu(): Boolean {
        val code = code()
        val row = code.indexOfFirst { it.opcode == Opcode.SGET_OBJECT && (it as ReferenceInstruction).reference.toString() == DOWNLOAD }
        return row >= 0 && code.drop(row + 1).any { instruction ->
            val called = instruction.method()?.parameterTypes?.map(Any::toString).orEmpty()
            instruction.opcode in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE) && OPTION in called && list in called
        }
    }

    @Test
    fun theFeedMenusBuilderIsNeverTakenForAReelMenus() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertTrue("three builds of the declared version", bundles.size >= 3)
        val kept = LinkedHashMap<String, Int>()
        var merged = false
        for (bundle in bundles) {
            val name = if (bundle.extension == "apks") bundle.name else bundle.parentFile.name
            val helper = loader(bundle, EDITING_TOOLS_QUERY).definingClass
            val flow = loader(bundle, ELIGIBILITY_PATH)
            val asked = flow.code().mapNotNull { it.method()?.takeIf { called -> called.definingClass == flow.definingClass } }
            val flowClass = FixtureDex.classes(bundle, setOf(flow.definingClass)).getValue(flow.definingClass)
            val eligible = flowClass.methods.filter { method ->
                val takes = method.parameterTypes.map(Any::toString)
                method.returnType == "Z" && takes.size == 2 && session in takes && takes.all { it.startsWith("L") } &&
                    asked.any { it.name == method.name && it.parameterTypes.map(Any::toString) == takes }
            }.single()
            val loaders = FixtureDex.methodsWhere(bundle, { true }) { method ->
                val code = method.code()
                code.any { it.calls(eligible) } &&
                    code.any { it.opcode == Opcode.SGET_OBJECT && (it as ReferenceInstruction).reference.toString() == DOWNLOAD }
            }
            val feed = loaders.filter { it.addsToTheFeedMenu() }
            assertEquals("$name: one builder of the feed menu adds Download after the download check", 1, feed.size)
            val touchesHelper = feed.single().code().any { instruction ->
                when (val reference = (instruction as? ReferenceInstruction)?.reference) {
                    is FieldReference -> reference.definingClass == helper
                    is MethodReference -> reference.definingClass == helper
                    else -> false
                }
            }
            merged = merged || touchesHelper
            val builders = loaders.filter { it.buildsReelMenu(helper, eligible) }
            val where = feed.single().let { "${it.definingClass}->${it.name}" }
            assertFalse("$name: $where, the feed menu's builder, taken for a reel menu's", feed.single() in builders)
            assertTrue("$name: no builder of the reel menu", builders.isNotEmpty())
            kept[name] = builders.size
        }
        assertTrue("a build whose feed menu builder shares a method with the reel menu ($kept)", merged)
        assertEquals("the same reel menu builders on every build: $kept", 1, kept.values.toSet().size)
    }
}

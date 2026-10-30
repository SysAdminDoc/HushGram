/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide the caught up card (#307): in every declared build the end of feed card's binder calls
 * CaughtUpCard.bind with its row and card first thing, the details stub reads the card's details,
 * whose kind enum names TOP_OF_FEED, and the caught up card's title hook still goes in beside it.
 */
class HideCaughtUpCardTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(CAUGHT_UP_CARD).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$CAUGHT_UP_CARD->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$CAUGHT_UP_BIND is not in the extension: $declared", CAUGHT_UP_BIND in declared)
        assertTrue("the details stub is not in the extension: $declared", "$CAUGHT_UP_CARD->$CAUGHT_UP_DETAILS_STUB(Ljava/lang/Object;)Ljava/lang/Object;" in declared)
    }

    @Test
    fun eachDeclaredBuildFoldsTheCard() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                foldsTheCard(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildFoldsTheCard() {
        var checked = 0
        for (apk in Fixtures.otherBuilds()) {
            foldsTheCard(apk, apk.parentFile.name)
            checked++
        }
        assertTrue("no other build", checked > 0)
    }

    /** Without the kind's enum, the card's details can't be told apart, and the find stops. */
    @Test
    fun aCardWithoutItsKindFailsTheFind() {
        val bundle = declaredBundle()
        val classes = cardClasses(bundle)
        val kinds = classes.filter { it.superclass == "Ljava/lang/Enum;" && it.methods.any { m -> m.code().any { i -> i.loads(TOP_OF_FEED) } } }
        assertTrue("the kind enum is among the card's classes", kinds.isNotEmpty())
        val failure = assertThrows(PatchException::class.java) {
            PatchContexts.of(classes.filter { it !in kinds } + ExtensionDex.classDef(CAUGHT_UP_CARD)).findHideCaughtUpCard()
        }
        assertTrue(failure.message!!, failure.message!!.contains("found 0"))
    }

    private fun declaredBundle(): File {
        val version = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.first()
        return Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }.first()
    }

    /** The binder, the classes it names and the classes they name. */
    private fun cardClasses(bundle: File): List<ClassDef> {
        val binders = FixtureDex.classesHolding(bundle, END_CARD_HOLDER)
            .filter { binder -> binder.methods.any { method -> method.code().any { it.loads(END_CARD_UNIT) } } }
        assertTrue("${bundle.name}: no class holds both of the card's casts", binders.isNotEmpty())
        val named = FixtureDex.classes(bundle, binders.typesNamed()).values
        val walked = FixtureDex.classes(bundle, named.typesNamed()).values
        return (binders + named + walked).distinctBy { it.type }
    }

    /**
     * Both of the card's hooks found before either is written, the title's first: bindView starts with
     * the range call on p2 and p3, the title's hook is still there once, and the details stub reads the
     * card's details.
     */
    private fun foldsTheCard(bundle: File, label: String) {
        val context = PatchContexts.of(
            cardClasses(bundle) + ExtensionDex.classDef(CAUGHT_UP_CARD) + ExtensionDex.classDef(FEED_SUGGESTIONS),
        )
        val title = context.findCaughtUpCardTitle()
        val found = context.findHideCaughtUpCard()
        assertEquals("$label: the title's binder", title.binder, found.binder)
        assertEquals("$label: the title's card", title.cardType, found.cardType)
        title.write(context, itemsFilled = false)
        found.write(context)

        val bind = context.mutableClassDefBy(found.binder).methods.single {
            it.name == "bindView" && it.parameterTypes.map(CharSequence::toString) == BIND_VIEW
        }
        val code = bind.code()
        val first = code.first()
        assertEquals("$label: the fold comes first", Opcode.INVOKE_STATIC_RANGE to CAUGHT_UP_BIND, first.opcode to first.referenceText())
        val range = first as RegisterRangeInstruction
        val p2 = bind.implementation!!.registerCount - 3
        assertEquals("$label: with the row and the card", p2 to 2, range.startRegister to range.registerCount)
        assertEquals("$label: one fold", 1, code.count { it.referenceText() == CAUGHT_UP_BIND })
        assertEquals("$label: the title's hook is still in", 1, code.count { it.referenceText() == CAUGHT_UP_TITLE })

        assertTrue("$label: details ${found.details} is a field of the card", found.details.startsWith("${found.cardType}->"))
        val stub = context.mutableClassDefBy(CAUGHT_UP_CARD).methods.single { it.name == CAUGHT_UP_DETAILS_STUB }
        assertEquals(
            "$label: the details stub reads the card's details",
            listOf(Opcode.CHECK_CAST to found.cardType, Opcode.IGET_OBJECT to found.details, Opcode.RETURN_OBJECT to null),
            stub.code().take(3).map { it.opcode to it.referenceText() },
        )
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.loads(value: String) =
        ((this as? ReferenceInstruction)?.reference as? StringReference)?.string == value

    /** The app's classes the code of these classes names: casts, calls' owners and answers, fields' owners and types. */
    private fun Collection<ClassDef>.typesNamed(): Set<String> = flatMap { classDef ->
        classDef.methods.flatMap { it.code() }.flatMap { instruction ->
            when (val reference = (instruction as? ReferenceInstruction)?.reference) {
                is TypeReference -> listOf(reference.type)
                is MethodReference -> listOf(reference.definingClass, reference.returnType) + reference.parameterTypes.map(CharSequence::toString)
                is FieldReference -> listOf(reference.definingClass, reference.type)
                else -> emptyList()
            }
        }
    }.filter { it.startsWith("L") && !it.startsWith("Ljava/") && !it.startsWith("Landroid/") }.toSet()

    private companion object {
        val BIND_VIEW = listOf("I", "Landroid/view/View;", "Ljava/lang/Object;", "Ljava/lang/Object;")
    }
}

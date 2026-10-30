/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.morphe.patches.instagram

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Reads the classes a fixture test needs out of an Instagram bundle's base APK, or out of a plain
 * base APK (an `.apk` file, as [app.morphe.Fixtures.otherBuilds] hands out), one dex at a time, so
 * no APK lands on disk and no more than one dex is held at once. What it hands back are immutable
 * copies, which keep none of the dex they came from.
 */
internal object FixtureDex {
    private val DEX = Regex("""classes\d*\.dex""")

    private fun forEachDex(bundle: File, visit: (DexBackedDexFile) -> Unit) {
        if (bundle.extension == "apk") {
            ZipFile(bundle).use { apk ->
                for (entry in apk.entries().asSequence().filter { DEX.matches(it.name) }.toList()) {
                    visit(DexBackedDexFile(Opcodes.getDefault(), ByteBuffer.wrap(apk.getInputStream(entry).use { it.readBytes() })))
                }
            }
            return
        }
        ZipFile(bundle).use { zip ->
            val base = checkNotNull(zip.getEntry("base.apk")) { "${bundle.name} holds no base.apk" }
            ZipInputStream(zip.getInputStream(base).buffered()).use { apk ->
                while (true) {
                    val entry = apk.nextEntry ?: break
                    if (DEX.matches(entry.name)) visit(DexBackedDexFile(Opcodes.getDefault(), ByteBuffer.wrap(apk.readBytes())))
                }
            }
        }
    }

    /**
     * Every dex of the bundle, one at a time, for a test that looks for several things in one
     * pass. Whatever [visit] keeps has to be copied out, as the other readers here do.
     */
    fun forEach(bundle: File, visit: (DexBackedDexFile) -> Unit) = forEachDex(bundle, visit)

    /** The classes of [types] the bundle carries, by type. */
    fun classes(bundle: File, types: Set<String>): Map<String, ClassDef> {
        val found = mutableMapOf<String, ClassDef>()
        forEachDex(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.type in types && classDef.type !in found) found[classDef.type] = ImmutableClassDef.of(classDef)
            }
        }
        return found
    }

    /** The classes whose direct superclass is one of [supers] in the bundle. */
    fun subclasses(bundle: File, supers: Set<String>): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        forEachDex(bundle) { dex ->
            for (classDef in dex.classes) if (classDef.superclass in supers) found += ImmutableClassDef.of(classDef)
        }
        return found
    }

    /**
     * [classes] plus the string pools they call. From 450 Redex moves many strings into static
     * (int)String switch methods, and a patch reading a pooled string needs the pool's class to
     * resolve it, so a test that slices the fixture has to carry those classes too.
     */
    fun withStringPools(bundle: File, classes: Collection<ClassDef>): List<ClassDef> {
        val have = classes.mapTo(HashSet()) { it.type }
        val pools = classes.asSequence().flatMap { it.methods.asSequence() }
            .flatMap { it.implementation?.instructions?.asSequence().orEmpty() }
            .mapNotNull { ((it as? ReferenceInstruction)?.reference as? MethodReference) }
            .filter { it.returnType == "Ljava/lang/String;" && it.parameterTypes.map(Any::toString) == listOf("I") }
            .map { it.definingClass }.filter { it !in have }.toSet()
        return classes.toList() + if (pools.isEmpty()) emptyList() else classes(bundle, pools).values
    }

    /**
     * The classes of [types] as the bundle's dex files hand them out, not copied. Like the patcher
     * reading an APK, each read of a method's code makes new instruction objects, so a patch that
     * looks an instruction up again by the object rather than by its position fails here as it would
     * on a phone's APK, where the copies [classes] makes keep one object per instruction.
     */
    fun classesAsRead(bundle: File, types: Set<String>): Map<String, ClassDef> {
        val found = mutableMapOf<String, ClassDef>()
        forEachDex(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.type in types && classDef.type !in found) found[classDef.type] = classDef
            }
        }
        return found
    }

    /**
     * Every method that [wanted] picks, in the dex files [dexFilter] lets through. The filter is how
     * a whole-APK search stays quick: a call needs its target in the dex's method section, and a
     * name needs to be in its string section, so a dex without them can be skipped unread.
     */
    fun methodsWhere(bundle: File, dexFilter: (DexBackedDexFile) -> Boolean, wanted: (Method) -> Boolean): List<Method> {
        val found = mutableListOf<Method>()
        forEachDex(bundle) { dex ->
            if (!dexFilter(dex)) return@forEachDex
            for (classDef in dex.classes) {
                for (method in classDef.methods) {
                    if (wanted(method)) found += ImmutableMethod.of(method)
                }
            }
        }
        return found
    }

    /** Every class with a method that loads exactly [string]. Only the dex files holding it are walked. */
    fun classesHolding(bundle: File, string: String): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        forEachDex(bundle) { dex ->
            if (dex.stringSection.none { it == string }) return@forEachDex
            for (classDef in dex.classes) {
                if (classDef.methods.any { holdsString(it, string) }) found += ImmutableClassDef.of(classDef)
            }
        }
        return found
    }

    /**
     * Every class with a method that makes a new instance of one of [types]. 452's Redex inlined the
     * Feed action-row state's constructor into the method that builds it, so a test of the state's
     * flags carries that class too.
     */
    fun classesCreating(bundle: File, types: Set<String>): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        forEachDex(bundle) { dex ->
            if (dex.typeSection.none { it in types }) return@forEachDex
            for (classDef in dex.classes) {
                if (classDef.type in types) continue
                val creates = classDef.methods.any { method ->
                    method.implementation?.instructions?.any {
                        it.opcode == com.android.tools.smali.dexlib2.Opcode.NEW_INSTANCE &&
                            ((it as com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction).reference as com.android.tools.smali.dexlib2.iface.reference.TypeReference).type in types
                    } == true
                }
                if (creates) found += ImmutableClassDef.of(classDef)
            }
        }
        return found
    }

    /**
     * [classesHolding] plus every class asking a static `(int)String` pool for [string] by a number
     * loaded right before the call, and the pools. Which strings Redex pools differs from build to
     * build (#77): 451's 385713073 asks a pool for friendship_statuses that 450 held.
     */
    fun classesLoading(bundle: File, string: String): List<ClassDef> {
        val found = classesHolding(bundle, string).associateByTo(LinkedHashMap()) { it.type }
        val pools = HashMap<String, Set<Int>>()
        for (holder in found.values) {
            for (method in holder.methods) poolNumbers(method, string).takeIf { it.isNotEmpty() }?.let { pools["${holder.type}->${method.name}"] = it }
        }
        if (pools.isEmpty()) return found.values.toList()
        forEachDex(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.type !in found && classDef.methods.any { asksPool(it, pools) }) found[classDef.type] = ImmutableClassDef.of(classDef)
            }
        }
        return found.values.toList()
    }

    /** Whether [method] calls one of [pools] (by "type->name") with a number it answers, loaded right before the call. */
    private fun asksPool(method: Method, pools: Map<String, Set<Int>>): Boolean {
        val code = method.implementation?.instructions?.toList() ?: return false
        return code.indices.any { at ->
            val call = code[at]
            val pool = (call as? ReferenceInstruction)?.reference as? MethodReference ?: return@any false
            val numbers = pools["${pool.definingClass}->${pool.name}"] ?: return@any false
            val number = code.getOrNull(at - 1) as? NarrowLiteralInstruction ?: return@any false
            call.opcode == Opcode.INVOKE_STATIC && pool.parameterTypes.map(Any::toString) == listOf("I") &&
                (number as OneRegisterInstruction).registerA == (call as Instruction35c).registerC && number.narrowLiteral in numbers
        }
    }

    /** The numbers a static `(int)String` pool answers [string] for; the first switch whose case for a number loads a string decides it. */
    private fun poolNumbers(method: Method, string: String): Set<Int> {
        if (!AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "Ljava/lang/String;" ||
            method.parameterTypes.map(Any::toString) != listOf("I")
        ) {
            return emptySet()
        }
        val code = method.implementation?.instructions?.toList() ?: return emptySet()
        val address = IntArray(code.size + 1)
        code.forEachIndexed { index, instruction -> address[index + 1] = address[index] + instruction.codeUnits }
        val at = (0 until code.size).associateBy { address[it] }
        val answered = HashMap<Int, String>()
        for (switch in code.indices) {
            if (code[switch].opcode != Opcode.PACKED_SWITCH && code[switch].opcode != Opcode.SPARSE_SWITCH) continue
            val payload = at[address[switch] + (code[switch] as OffsetInstruction).codeOffset]?.let { code[it] } as? SwitchPayload ?: continue
            for (case in payload.switchElements) {
                if (case.key in answered) continue
                at[address[switch] + case.offset]?.let { code[it] }
                    ?.takeIf { it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO }
                    ?.let { answered[case.key] = ((it as ReferenceInstruction).reference as StringReference).string }
            }
        }
        return answered.filterValues { it == string }.keys
    }

    /** Whether [method] loads exactly [string]. */
    private fun holdsString(method: Method, string: String): Boolean =
        method.implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
        } == true
}

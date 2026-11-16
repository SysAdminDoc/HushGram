/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.ring

import app.morphe.patches.instagram.FixtureDex
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction51l
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import java.io.File

/**
 * The ring view, default ring and Compose ring fixtures. They live outside the test classes so the
 * accent ring tests in misc can use them: an area's test run stages another area's helpers, never
 * its test classes or their companions.
 */
internal object StoryRingFixtures {
    const val RING_SPEC = "Lfixture/DefaultRing;"
    private const val TILE_MODE = "Landroid/graphics/Shader\$TileMode;"
    const val COMPOSE_RING = "Lfixture/ComposeRing;"

    /** Where the array lands in [composeRing] with a range call. */
    const val HIGH_RESULT = 20

    private const val LIST = "Ljava/util/List;"

    /**
     * The ring view with a builder shaped like Instagram's: fifteen registers, the last seven
     * its parameters, so the stops arrive in v9 and the colors in v12.
     *
     *     a0  move-object/from16 v7, v9      the stops
     *     a2  move-object/from16 v6, v12     the colors
     *     a4  if-eqz v9, :fallback
     *     a6  const/4 v2, 0
     *     a7  sget-object v8, Shader.TileMode.CLAMP
     *     a9  new-instance v1, LinearGradient
     *     a11 invoke-direct/range {v1 .. v8}, LinearGradient.<init>
     *     a14 return-object v1
     *     a15 :fallback const/4 v1, 0
     *     a16 return-object v1
     *     a17 goto a0                        only with [loops]
     *
     * With [setters] it also gets that many private setters, `keep` then `keepAgain`, taking
     * [SETTER_PARAMETERS] with the ring as `this` in v8, so their stops and colors arrive in v9
     * and v12 too. Each has the builder's code with return-void for each return. With
     * [setterMakesItsOwn] false a setter makes its gradient from two ints instead, the way one
     * that hands its arrays to the builder never makes one from arrays itself, and with
     * [setterLoops] it ends jumping back to its start. With [elsewhere], a private `drawn(I)V`
     * makes a gradient from arrays as well.
     */
    fun spinner(
        builders: Int = 1,
        fromArrays: Boolean = true,
        loops: Boolean = false,
        registers: Int = 15,
        setters: Int = 0,
        setterMakesItsOwn: Boolean = true,
        setterLoops: Boolean = false,
        elsewhere: Boolean = false,
    ): ClassDef {
        val first = registers - GRADIENT_PARAMETERS.size
        val stops = first + STOPS_PARAMETER
        val colors = first + COLORS_PARAMETER
        fun code(arrays: Boolean, returnsGradient: Boolean, loop: Boolean): List<Instruction> {
            val made = ImmutableMethodReference(
                LINEAR_GRADIENT, "<init>",
                if (arrays) listOf("F", "F", "F", "F", "[I", "[F", TILE_MODE) else listOf("F", "F", "F", "F", "I", "I", TILE_MODE),
                "V",
            )
            fun returns(): Instruction =
                if (returnsGradient) ImmutableInstruction11x(Opcode.RETURN_OBJECT, 1) else ImmutableInstruction10x(Opcode.RETURN_VOID)
            val code = mutableListOf<Instruction>(
                ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 7, stops),
                ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 6, colors),
                ImmutableInstruction21t(Opcode.IF_EQZ, stops, 11),
                ImmutableInstruction11n(Opcode.CONST_4, 2, 0),
                ImmutableInstruction21c(Opcode.SGET_OBJECT, first, ImmutableFieldReference(TILE_MODE, "CLAMP", TILE_MODE)),
                ImmutableInstruction21c(Opcode.NEW_INSTANCE, 1, ImmutableTypeReference(LINEAR_GRADIENT)),
                ImmutableInstruction3rc(Opcode.INVOKE_DIRECT_RANGE, 1, 8, made),
                returns(),
                ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
                returns(),
            )
            if (loop) code += ImmutableInstruction10t(Opcode.GOTO, -17)
            return code
        }
        val builder = code(fromArrays, returnsGradient = true, loop = loops)
        val methods = listOf("build", "buildAgain").take(builders).map { name ->
            ImmutableMethod(
                GRADIENT_SPINNER, name, GRADIENT_PARAMETERS.map { ImmutableMethodParameter(it, null, null) }, LINEAR_GRADIENT,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(registers, builder, null, null),
            )
        } + listOf("keep", "keepAgain").take(setters).map { name ->
            ImmutableMethod(
                GRADIENT_SPINNER, name, SETTER_PARAMETERS.map { ImmutableMethodParameter(it, null, null) }, "V",
                AccessFlags.PRIVATE.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(registers, code(setterMakesItsOwn, returnsGradient = false, loop = setterLoops), null, null),
            )
        } + listOfNotNull(
            ImmutableMethod(
                GRADIENT_SPINNER, "drawn", listOf(ImmutableMethodParameter("I", null, null)), "V",
                AccessFlags.PRIVATE.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(registers, code(arrays = true, returnsGradient = false, loop = false), null, null),
            ).takeIf { elsewhere },
        )
        return ImmutableClassDef(
            GRADIENT_SPINNER, AccessFlags.PUBLIC.value, "Landroid/view/View;",
            null, null, null, null, methods,
        )
    }

    /**
     * The class that puts the default ring together: one method loading the ring's name and its
     * stops as doubles. With [named] false it loads another ring's name.
     */
    fun defaultRing(type: String = RING_SPEC, named: Boolean = true, stops: List<Long> = DEFAULT_RING_STOPS): ClassDef {
        val code = mutableListOf<Instruction>(
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(if (named) DEFAULT_RING else "rainbow")),
        )
        stops.forEach { code += ImmutableInstruction51l(Opcode.CONST_WIDE, 1, it) }
        code += ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)
        val method = ImmutableMethod(
            type, "invoke", emptyList(), "Ljava/lang/Object;",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(4, code, null, null),
        )
        return ImmutableClassDef(
            type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            null, null, null, null, listOf(method),
        )
    }

    /**
     * A class shaped like 454's `LX/0IDN;`: a static `A00()` that loads [DEFAULT_RING], reads the
     * ring's colors as a list and turns them into an array with a static taking a list.
     *
     *     a0  const-string v0, "default_brand_update"
     *     a2  sget-object v1, colors                 [calls] times, with the next two
     *     a4  invoke-static {v1}, A01(List)[I         as a range with [range]
     *     a7  move-result-object v6                   v20 with [range], left out without [moved]
     *     a8  array-length v5, v6                     left out with [range]
     *     a9  return-object v0
     *     a10 goto a8                                 only with [jumpsAfter]
     *
     * With [unnamedCaller] the class gets an `A01()` making the same call without loading the name.
     */
    fun composeRing(
        type: String = COMPOSE_RING,
        calls: Int = 1,
        range: Boolean = false,
        answers: String = "[I",
        moved: Boolean = true,
        jumpsAfter: Boolean = false,
        unnamedCaller: Boolean = false,
    ): ClassDef {
        val result = if (range) HIGH_RESULT else 6
        val toArray = ImmutableMethodReference("Lfixture/Colors;", "A01", listOf(LIST), answers)
        fun code(named: Boolean): List<Instruction> {
            val code = mutableListOf<Instruction>(
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(if (named) DEFAULT_RING else "rainbow")),
            )
            repeat(calls) {
                code += ImmutableInstruction21c(Opcode.SGET_OBJECT, 1, ImmutableFieldReference("Lfixture/RingSpec;", "colors", LIST))
                code += if (range) {
                    ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 1, 1, toArray)
                } else {
                    ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 1, 0, 0, 0, 0, toArray)
                }
                if (moved) code += ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, result)
            }
            if (!range) code += ImmutableInstruction12x(Opcode.ARRAY_LENGTH, 5, 6)
            code += ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)
            if (jumpsAfter) {
                val target = code.take(4).sumOf { it.codeUnits }
                code += ImmutableInstruction10t(Opcode.GOTO, target - code.sumOf { it.codeUnits })
            }
            return code
        }
        fun method(name: String, named: Boolean) = ImmutableMethod(
            type, name, emptyList(), "Ljava/lang/Object;",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(if (range) 24 else 8, code(named), null, null),
        )
        val methods = listOf(method("A00", named = true)) + listOfNotNull(method("A01", named = false).takeIf { unnamedCaller })
        return ImmutableClassDef(
            type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            null, null, null, null, methods,
        )
    }

    /** The default ring's own class on 455, which [ringMaker] makes and [composeRingTaker] takes. */
    const val RING_HOLDER = "Lfixture/RingHolder;"
    const val COMPOSE_RING_TAKER = "Lfixture/ComposeRingTaker;"

    /**
     * A class shaped like 455's lambda putting the default ring together: its `invoke()` loads
     * [DEFAULT_RING] and makes [RING_HOLDER] from the name, two lists and four doubles, or from
     * [lists] lists.
     *
     *     a0 const-string v0, "default_brand_update"
     *     a2 new-instance v1, RingHolder
     *     a4 invoke-direct/range {v1 ..}, RingHolder.<init>(String, List, List, D, D, D, D)V
     *     a7 return-object v1
     */
    fun ringMaker(lists: Int = 2): ClassDef {
        val parameters = listOf("Ljava/lang/String;") + List(lists) { LIST } + List(4) { "D" }
        val made = ImmutableMethodReference(RING_HOLDER, "<init>", parameters, "V")
        val code = listOf<Instruction>(
            ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(DEFAULT_RING)),
            ImmutableInstruction21c(Opcode.NEW_INSTANCE, 1, ImmutableTypeReference(RING_HOLDER)),
            ImmutableInstruction3rc(Opcode.INVOKE_DIRECT_RANGE, 1, 1 + lists + 1 + 8, made),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 1),
        )
        val method = ImmutableMethod(
            "Lfixture/RingMaker;", "invoke", emptyList(), "Ljava/lang/Object;",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(16, code, null, null),
        )
        return ImmutableClassDef(
            "Lfixture/RingMaker;", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            null, null, null, null, listOf(method),
        )
    }

    /**
     * A class shaped like 455's `LX/0129;`: a static `A00` taking only [takes] that reads the ring's
     * colors from it as a list, turns them into an array with a static taking a list, and reads the
     * array. Its one parameter is v8.
     *
     *     a0 iget-object v0, v8, colors
     *     a2 invoke-static {v0}, A01(List)[I
     *     a5 move-result-object v6
     *     a6 array-length v5, v6
     *     a7 return-object v6
     */
    fun composeRingTaker(takes: String = RING_HOLDER): ClassDef {
        val toArray = ImmutableMethodReference("Lfixture/Colors;", "A01", listOf(LIST), "[I")
        val code = listOf<Instruction>(
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 8, ImmutableFieldReference(takes, "colors", LIST)),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0, toArray),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 6),
            ImmutableInstruction12x(Opcode.ARRAY_LENGTH, 5, 6),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 6),
        )
        val method = ImmutableMethod(
            COMPOSE_RING_TAKER, "A00", listOf(ImmutableMethodParameter(takes, null, null)), "Ljava/lang/Object;",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(9, code, null, null),
        )
        return ImmutableClassDef(
            COMPOSE_RING_TAKER, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            null, null, null, null, listOf(method),
        )
    }

    /**
     * The classes of [bundle] the Compose ring is looked for in: those loading [DEFAULT_RING] and
     * those with a method taking only a class one of them makes from two lists and four doubles,
     * which is where 455 turns the ring's colors into an array.
     */
    fun composeRingClasses(bundle: File): List<ClassDef> {
        val named = FixtureDex.classesLoading(bundle, DEFAULT_RING)
        val rings = named.asSequence().flatMap { it.methods.asSequence() }
            .flatMap { it.implementation?.instructions?.toList().orEmpty() }
            .mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }
            .filter { call ->
                val types = call.parameterTypes.map(CharSequence::toString)
                call.name == "<init>" && types.count { it == LIST } == 2 && types.count { it == "D" } == 4
            }
            .mapTo(HashSet()) { it.definingClass }
        if (rings.isEmpty()) return named
        val takers = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.typeSection.none { it in rings }) return@forEach
            for (classDef in dex.classes) {
                val takes = classDef.methods.any { method -> method.parameterTypes.map(CharSequence::toString).singleOrNull() in rings }
                if (takes) takers += ImmutableClassDef.of(classDef)
            }
        }
        return (named + takers).distinctBy { it.type }
    }
}

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
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
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
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31i
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
     *
     * With [inactiveSetter] it also gets a public `setInactiveColour(I)V`, the setter a seen ring's
     * gray goes through, shaped like 457's: six registers, the color in v5, read where the ring's
     * shader is drawn. With [inactiveLoops] it ends jumping back to its start.
     *
     *     a0  iget-object v3, v4, A0V        the inactive paint
     *     a2  move v1, v5                    the color
     *     a3  return-void
     *     a4  goto a0                        only with [inactiveLoops]
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
        inactiveSetter: Boolean = true,
        inactiveLoops: Boolean = false,
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
        ) + listOfNotNull(Unit.takeIf { inactiveSetter }).map {
            val inactive = mutableListOf<Instruction>(
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 3, 4, ImmutableFieldReference(GRADIENT_SPINNER, "A0V", "Landroid/graphics/Paint;")),
                ImmutableInstruction12x(Opcode.MOVE, 1, 5),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            if (inactiveLoops) inactive += ImmutableInstruction10t(Opcode.GOTO, -4)
            ImmutableMethod(
                GRADIENT_SPINNER, "setInactiveColour", listOf(ImmutableMethodParameter("I", null, null)), "V",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(6, inactive, null, null),
            )
        }
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

    const val RING_BRUSH = "Lfixture/RingBrush;"

    /**
     * A class shaped like 457's `LX/0G9f;`, whose static `A01(List)` makes the chat list's ring brush
     * (#683): its set-up loads [stops] as floats and keeps a list of them in `A02`, and [builders]
     * statics taking only a list read it, `A01` then `A03`. Each has fourteen registers, so its list
     * arrives in v13. With [loops] each ends jumping back to its start.
     *
     *     <clinit>  const v0, stop        once for each of [stops]
     *               const/4 v1, 0
     *               sput-object v1, A02
     *               return-void
     *     A01       a0 sget-object v6, A02
     *               a2 return-object v13
     *               a3 goto a0             only with [loops]
     */
    fun composeBrush(stops: List<Int> = COMPOSE_RING_STOPS, builders: Int = 1, loops: Boolean = false): ClassDef {
        val kept = ImmutableFieldReference(RING_BRUSH, "A02", LIST)
        val setUp = mutableListOf<Instruction>()
        stops.forEach { setUp += ImmutableInstruction31i(Opcode.CONST, 0, it) }
        setUp += ImmutableInstruction11n(Opcode.CONST_4, 1, 0)
        setUp += ImmutableInstruction21c(Opcode.SPUT_OBJECT, 1, kept)
        setUp += ImmutableInstruction10x(Opcode.RETURN_VOID)
        val methods = mutableListOf(
            ImmutableMethod(
                RING_BRUSH, "<clinit>", emptyList(), "V",
                AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value, null, null,
                ImmutableMethodImplementation(2, setUp, null, null),
            ),
        )
        for (name in listOf("A01", "A03").take(builders)) {
            val code = mutableListOf<Instruction>(
                ImmutableInstruction21c(Opcode.SGET_OBJECT, 6, kept),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 13),
            )
            if (loops) code += ImmutableInstruction10t(Opcode.GOTO, -code.sumOf { it.codeUnits })
            methods += ImmutableMethod(
                RING_BRUSH, name, listOf(ImmutableMethodParameter(LIST, null, null)), "Ljava/lang/Object;",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(14, code, null, null),
            )
        }
        return ImmutableClassDef(
            RING_BRUSH, AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, "Ljava/lang/Object;",
            null, null, null, null, methods,
        )
    }

    const val GROUP_RING = "Lfixture/GroupRing;"

    /** What [groupRing] pairs a stop and a color with, shaped like 457's `LX/0ArC;->A0f(FJ)`. */
    private val PAIR = ImmutableMethodReference("Lfixture/Stops;", "A0f", listOf("F", "J"), "Lfixture/Stop;")

    /** What [groupRing] pairs a boxed stop and a color with, shaped like 386314341's `LX/089w;->A0t(Ljava/lang/Object;J)`. */
    private val PAIR_BOXED = ImmutableMethodReference("Lfixture/Stops;", "A0t", listOf("Ljava/lang/Object;", "J"), "Lfixture/Stop;")
    private val BOX = ImmutableMethodReference("Ljava/lang/Float;", "valueOf", listOf("F"), "Ljava/lang/Float;")

    /**
     * A class shaped like 457's `LX/0N9F;`, whose `A01` draws the spinning ring around a group of
     * profile pictures (#187): [rings] statics (`A01`, then `A03`), each pairing a color copied from
     * v4 with each of [stops] in turn, the first through a range call, the rest through v0. Eight
     * registers, the last its parameter. With [jumps] each ends jumping onto its second pairing. With
     * [boxed], as on 386314341, each stop is boxed as a Float before an object and long pairing takes it.
     *
     *     const v1, stop                      the first stop (boxed: const v0, then
     *                                         invoke-static { v0 }, valueOf and move-result-object v1)
     *     move-wide v2, v4                    its color
     *     invoke-static/range { v1 .. v3 }, pair
     *     move-result-object v6
     *     const v0, stop                      then each other stop (boxed: invoke-static { v0 }, valueOf
     *                                         and move-result-object v0 after it)
     *     move-wide v2, v4
     *     invoke-static { v0, v2, v3 }, pair
     *     move-result-object v6
     *     return-void
     *     goto <the second pairing>           only with [jumps]
     */
    fun groupRing(stops: List<Int> = COMPOSE_RING_STOPS, rings: Int = 1, jumps: Boolean = false, boxed: Boolean = false): ClassDef {
        val pair = if (boxed) PAIR_BOXED else PAIR
        val methods = listOf("A01", "A03").take(rings).map { name ->
            val code = mutableListOf<Instruction>()
            var second = 0
            stops.forEachIndexed { at, stop ->
                val holder = if (at == 0) 1 else 0
                if (boxed) {
                    code += ImmutableInstruction31i(Opcode.CONST, 0, stop)
                    code += ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0, BOX)
                    code += ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, holder)
                } else {
                    code += ImmutableInstruction31i(Opcode.CONST, holder, stop)
                }
                code += ImmutableInstruction12x(Opcode.MOVE_WIDE, 2, 4)
                if (at == 1) second = code.sumOf { it.codeUnits }
                code += if (at == 0) {
                    ImmutableInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 1, 3, pair)
                } else {
                    ImmutableInstruction35c(Opcode.INVOKE_STATIC, 3, 0, 2, 3, 0, 0, pair)
                }
                code += ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 6)
            }
            code += ImmutableInstruction10x(Opcode.RETURN_VOID)
            if (jumps) code += ImmutableInstruction10t(Opcode.GOTO, second - code.sumOf { it.codeUnits })
            ImmutableMethod(
                GROUP_RING, name, listOf(ImmutableMethodParameter("Lfixture/Group;", null, null)), "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(8, code, null, null),
            )
        }
        return ImmutableClassDef(
            GROUP_RING, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            null, null, null, null, methods,
        )
    }

    /**
     * The classes of [bundle] the group ring is looked for in: those with a method other than a set-up
     * loading the last of [COMPOSE_RING_STOPS].
     */
    fun groupRingClasses(bundle: File): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                val loads = classDef.methods.any { method ->
                    method.name != "<clinit>" && method.implementation?.instructions?.any {
                        it.opcode == Opcode.CONST && (it as NarrowLiteralInstruction).narrowLiteral == COMPOSE_RING_STOPS.last()
                    } == true
                }
                if (loads) found += ImmutableClassDef.of(classDef)
            }
        }
        return found
    }

    /**
     * The classes of [bundle] the chat list's ring brush is looked for in: those with a static list and a
     * set-up loading every one of [COMPOSE_RING_STOPS].
     */
    fun composeBrushClasses(bundle: File): List<ClassDef> {
        val found = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.staticFields.none { it.type == LIST }) continue
                val setUp = classDef.directMethods.firstOrNull { it.name == "<clinit>" } ?: continue
                val loaded = setUp.implementation?.instructions?.mapNotNullTo(HashSet()) {
                    (it as? NarrowLiteralInstruction)?.narrowLiteral
                } ?: continue
                if (loaded.containsAll(COMPOSE_RING_STOPS)) found += ImmutableClassDef.of(classDef)
            }
        }
        return found
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

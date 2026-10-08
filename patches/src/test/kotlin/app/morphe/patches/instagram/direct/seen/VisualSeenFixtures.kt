/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.seen

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference

/** Independent stand-ins exercise registration and callback mistakes without account data. */
    const val HANDLER = "Lfixture/VisualHandler;"
    const val VOICE = "Lfixture/VoiceHandler;"
    const val THREAD = "Lfixture/ThreadHandler;"
    const val BASE = "Lfixture/Mutation;"
    const val MUTATION = "Lfixture/VisualMutation;"
    const val CALLBACK = "Lfixture/Completion;"
    const val ERROR = "Lfixture/Failure;"
    const val RESPONSE = "Lfixture/Response;"
    const val FACTORY = "Lfixture/ResponseFactory;"
    const val PROVIDER = "Lfixture/VisualProvider;"
    const val WRAPPER = "Lfixture/ProviderWrapper;"
    const val REGISTRY = "Lfixture/Registry;"
    const val MANAGER = "Lfixture/Manager;"
    const val CREATOR = "Lfixture/VisualViewer;"
    const val TRACE = "Lfixture/Trace;"
    const val OBJECT = "Ljava/lang/Object;"
    const val STRING = "Ljava/lang/String;"
    val HANDLER_PARAMS = listOf(TRACE, CALLBACK, BASE)
    val COMPLETE = ImmutableMethodReference(CALLBACK, "complete", listOf(ERROR, STRING), "V")
    val CALLBACK_FIELD = ImmutableFieldReference(RESPONSE, "completion", CALLBACK)
    val PROVIDER_FIELD = ImmutableFieldReference(HANDLER, "provider", PROVIDER)

    fun classes(): List<ClassDef> = listOf(
        handler(), handler(VOICE, "voice_media"), handler(THREAD, "thread", "ordinary_thread_seen"),
        callback(), response(), factory(), provider(), registry(), selector(), creator(), manager(),
        clazz(VISUAL_SEEN, listOf(method(VISUAL_SEEN, "hold", emptyList(), "Z", 1,
            listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0), ImmutableInstruction11x(Opcode.RETURN, 0)), static = true))),
    )

    fun handler(type: String = HANDLER, kind: String = VISUAL_KIND, endpoint: String = VISUAL_ENDPOINT): ClassDef = clazz(type, listOf(
        method(type, "<clinit>", emptyList(), "V", 1, listOf(
            field(Opcode.SGET_OBJECT, 0, ImmutableFieldReference(PROVIDER, "instance", PROVIDER)),
            field(Opcode.SPUT_OBJECT, 0, ImmutableFieldReference(type, "provider", PROVIDER)),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        ), static = true),
        method(type, "send", HANDLER_PARAMS, "V", 6, listOf(
            typed(Opcode.CHECK_CAST, 5, MUTATION), text(0, endpoint), text(0, kind),
            ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 2, ImmutableFieldReference(type, "session", USER_SESSION)),
            call(Opcode.INVOKE_STATIC, listOf(0, 4), FACTORY, "make", listOf(USER_SESSION, CALLBACK), RESPONSE),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            call(Opcode.INVOKE_STATIC, listOf(0), "Lfixture/Network;", "enqueue", listOf(RESPONSE), "V"),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )),
    ))

    fun callback(): ClassDef = ImmutableClassDef(CALLBACK,
        AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value,
        OBJECT, null, null, null, null, listOf(ImmutableMethod(CALLBACK, "complete",
            listOf(ERROR, STRING).map { ImmutableMethodParameter(it, null, null) }, "V",
            AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null)))

    fun response(): ClassDef = clazz(RESPONSE, listOf(method(RESPONSE, "success", listOf(USER_SESSION, OBJECT), "V", 6, listOf(
        ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 3, CALLBACK_FIELD), text(1, SUCCESS_ANCHOR),
        ImmutableInstruction11n(Opcode.CONST_4, 1, 0), call(Opcode.INVOKE_INTERFACE, listOf(0, 1, 1), COMPLETE),
        ImmutableInstruction10x(Opcode.RETURN_VOID),
    ))))

    fun factory(): ClassDef = clazz(FACTORY, listOf(
        method(FACTORY, "make", listOf(USER_SESSION, CALLBACK), RESPONSE, 4, listOf(
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            call(Opcode.INVOKE_STATIC, listOf(0, 2, 3), FACTORY, "write", listOf(OBJECT, USER_SESSION, CALLBACK), RESPONSE),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        ), static = true),
        method(FACTORY, "write", listOf(OBJECT, USER_SESSION, CALLBACK), RESPONSE, 5, listOf(
            typed(Opcode.NEW_INSTANCE, 1, RESPONSE),
            call(Opcode.INVOKE_DIRECT, listOf(1), OBJECT, "<init>", emptyList(), "V"),
            ImmutableInstruction22c(Opcode.IPUT_OBJECT, 4, 1, CALLBACK_FIELD), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 1),
        ), static = true),
    ))

    fun provider(): ClassDef = clazz(PROVIDER, listOf(method(PROVIDER, "get", listOf(USER_SESSION), OBJECT, 3, listOf(
        typed(Opcode.NEW_INSTANCE, 0, HANDLER),
        call(Opcode.INVOKE_DIRECT, listOf(0), OBJECT, "<init>", emptyList(), "V"),
        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
    ))))

    fun registry(): ClassDef = clazz(REGISTRY, listOf(method(REGISTRY, "register", emptyList(), "V", 8, listOf(
        field(Opcode.SGET_OBJECT, 0, PROVIDER_FIELD), typed(Opcode.NEW_INSTANCE, 4, WRAPPER),
        call(Opcode.INVOKE_DIRECT, listOf(4, 0), WRAPPER, "<init>", listOf(PROVIDER), "V"),
        typed(Opcode.NEW_INSTANCE, 1, WRAPPER), call(Opcode.INVOKE_DIRECT, listOf(1, 0), WRAPPER, "<init>", listOf(PROVIDER), "V"),
        text(0, VISUAL_MUTATION), typed(Opcode.NEW_INSTANCE, 3, "Lfixture/Descriptor;"),
        call(Opcode.INVOKE_DIRECT, listOf(3, 7, 4, 1, 0), "Lfixture/Descriptor;", "<init>", listOf(OBJECT, WRAPPER, WRAPPER, STRING), "V"),
        ImmutableInstruction10x(Opcode.RETURN_VOID),
    ))))

    fun selector(): ClassDef {
        val code = mutableListOf<Instruction>(
            ImmutableInstruction22c(Opcode.INSTANCE_OF, 0, 1, ImmutableTypeReference(MUTATION)),
            ImmutableInstruction21t(Opcode.IF_EQZ, 0, 0), text(0, VISUAL_MUTATION), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            text(0, "ordinary_thread_seen"), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        )
        code[1] = ImmutableInstruction21t(Opcode.IF_EQZ, 0, offset(code, 1, 4))
        return clazz(BASE, listOf(method(BASE, "name", emptyList(), STRING, 2, code)))
    }

    fun manager(): ClassDef = clazz(MANAGER, listOf(method(MANAGER, "dispatch", listOf(BASE), "Z", 3, listOf(
        text(0, DISPATCH_ANCHOR), ImmutableInstruction11n(Opcode.CONST_4, 0, 1), ImmutableInstruction11x(Opcode.RETURN, 0),
    ))))

    fun creator(): ClassDef = clazz(CREATOR, listOf(method(CREATOR, "open", listOf(MANAGER), "V", 4, listOf(
        typed(Opcode.NEW_INSTANCE, 0, MUTATION), call(Opcode.INVOKE_DIRECT, listOf(0), BASE, "<init>", emptyList(), "V"),
        call(Opcode.INVOKE_VIRTUAL, listOf(3, 0), MANAGER, "dispatch", listOf(BASE), "Z"), ImmutableInstruction10x(Opcode.RETURN_VOID),
    ))))

    /** [classes] with [name] in [type] edited by [change], the class otherwise kept as it was, fields included. */
    fun replace(classes: List<ClassDef>, type: String, name: String, change: (MutableList<Instruction>) -> Unit): List<ClassDef> = classes.map { candidate ->
        if (candidate.type != type) candidate else ImmutableClassDef(type, candidate.accessFlags, candidate.superclass, candidate.interfaces,
            candidate.sourceFile, candidate.annotations, candidate.fields, candidate.methods.map { old ->
                if (old.name != name) old else old.visualCode().toMutableList().let { code ->
                    change(code)
                    ImmutableMethod(old.definingClass, old.name, old.parameters, old.returnType, old.accessFlags, old.annotations, old.hiddenApiRestrictions,
                        ImmutableMethodImplementation(old.implementation!!.registerCount, code, null, null))
                }
            })
    }

    fun typed(opcode: Opcode, register: Int, type: String): Instruction = ImmutableInstruction21c(opcode, register, ImmutableTypeReference(type))
    fun text(register: Int, value: String): Instruction = ImmutableInstruction21c(Opcode.CONST_STRING, register, ImmutableStringReference(value))
    fun field(opcode: Opcode, register: Int, value: ImmutableFieldReference): Instruction = ImmutableInstruction21c(opcode, register, value)
    fun call(opcode: Opcode, registers: List<Int>, owner: String, name: String, parameters: List<String>, returns: String): Instruction =
        call(opcode, registers, ImmutableMethodReference(owner, name, parameters, returns))
    fun call(opcode: Opcode, registers: List<Int>, reference: ImmutableMethodReference): Instruction {
        val r = registers + List(5 - registers.size) { 0 }
        return ImmutableInstruction35c(opcode, registers.size, r[0], r[1], r[2], r[3], r[4], reference)
    }
    fun offset(code: List<Instruction>, from: Int, to: Int): Int = code.take(to).sumOf { it.codeUnits } - code.take(from).sumOf { it.codeUnits }
    fun clazz(type: String, methods: List<Method>, flags: Int = AccessFlags.PUBLIC.value): ClassDef =
        ImmutableClassDef(type, flags, OBJECT, null, null, null, null, methods)
    fun method(type: String, name: String, parameters: List<String>, returns: String, registers: Int, code: List<Instruction>, static: Boolean = false): Method =
        ImmutableMethod(type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
            AccessFlags.PUBLIC.value or if (static) AccessFlags.STATIC.value else 0, null, null, ImmutableMethodImplementation(registers, code, null, null))

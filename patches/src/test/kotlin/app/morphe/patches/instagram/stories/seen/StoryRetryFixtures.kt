/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.seen

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableExceptionHandler
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.ImmutableTryBlock

/** Owned pending/in-flight maps and a snapshot retry loop shaped like the declared native build. */
internal fun storyQueueBase(owner: String, request: String, session: Boolean = true): ImmutableClassDef {
    val lock = "$owner->lock:Ljava/lang/Object;"
    val pending = "$owner->pending:Ljava/util/LinkedHashMap;"
    val flight = "$owner->flight:Ljava/util/Map;"
    val methods = mutableListOf<Method>()
    fun method(name: String, parameters: List<String>, returns: String, registers: Int, body: String,
        constructor: Boolean = false, monitor: Boolean = false, protected: Pair<Int, Int>? = null) {
        methods += storyQueueMethod(owner, name, parameters, returns, registers, body, constructor, monitor, protected)
    }
    method("<init>", listOf(USER_SESSION), "V", 3, """
        invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
        iput-object p1, p0, $owner->account:$USER_SESSION
        new-instance v0, Ljava/util/LinkedHashMap;
        invoke-direct { v0 }, Ljava/util/LinkedHashMap;-><init>()V
        iput-object v0, p0, $pending
        new-instance v0, Ljava/util/HashMap;
        invoke-direct { v0 }, Ljava/util/HashMap;-><init>()V
        iput-object v0, p0, $flight
        new-instance v0, Ljava/lang/Object;
        invoke-direct { v0 }, Ljava/lang/Object;-><init>()V
        iput-object v0, p0, $lock
        return-void
    """, constructor = true)
    if (session) method("A0H", emptyList(), USER_SESSION, 2, """
        iget-object v0, p0, $owner->account:$USER_SESSION
        return-object v0
    """)
    methods += ImmutableMethod(owner, "A0J", listOf(ImmutableMethodParameter("Ljava/lang/Object;", null, null)), request,
        AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null)
    method("A0K", emptyList(), "Ljava/lang/Integer;", 2, """
        const/4 v0, 0x0
        return-object v0
    """)
    method("A03", emptyList(), "I", 4, """
        iget-object v2, p0, $lock
        monitor-enter v2
        iget-object v0, p0, $pending
        invoke-virtual { v0 }, Ljava/util/AbstractMap;->size()I
        move-result v1
        iget-object v0, p0, $flight
        invoke-interface { v0 }, Ljava/util/Map;->size()I
        move-result v0
        add-int/2addr v1, v0
        monitor-exit v2
        return v1
        move-exception v0
        monitor-exit v2
        throw v0
    """, protected = 2 to 9)
    method("A05", emptyList(), "Ljava/util/ArrayList;", 4, """
        iget-object v2, p0, $lock
        monitor-enter v2
        iget-object v0, p0, $pending
        invoke-virtual { v0 }, Ljava/util/AbstractMap;->keySet()Ljava/util/Set;
        move-result-object v1
        new-instance v0, Ljava/util/ArrayList;
        invoke-direct { v0, v1 }, Ljava/util/ArrayList;-><init>(Ljava/util/Collection;)V
        monitor-exit v2
        return-object v0
        move-exception v0
        monitor-exit v2
        throw v0
    """, protected = 2 to 7)
    method("A04", listOf("Ljava/lang/String;"), "Ljava/lang/Object;", 5, """
        iget-object v2, p0, $lock
        monitor-enter v2
        iget-object v1, p0, $pending
        invoke-virtual { v1, p1 }, Ljava/util/AbstractMap;->containsKey(Ljava/lang/Object;)Z
        move-result v0
        if-nez v0, :get
        iget-object v1, p0, $flight
        :get
        invoke-interface { v1, p1 }, Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;
        move-result-object v0
        monitor-exit v2
        return-object v0
        move-exception v0
        monitor-exit v2
        throw v0
    """, protected = 2 to 9)
    method("A0G", listOf("Ljava/lang/String;"), "Z", 6, """
        const/4 v2, 0x0
        invoke-static { p1, v2 }, LX/04Zi;->A0V(Ljava/lang/Object;I)V
        iget-object v3, p0, $lock
        monitor-enter v3
        iget-object v1, p0, $pending
        invoke-virtual { v1, p1 }, Ljava/util/AbstractMap;->containsKey(Ljava/lang/Object;)Z
        move-result v0
        if-nez v0, :claim
        monitor-exit v3
        return v2
        :claim
        iget-object v2, p0, $flight
        invoke-virtual { v1, p1 }, Ljava/util/AbstractMap;->remove(Ljava/lang/Object;)Ljava/lang/Object;
        move-result-object v1
        const-string v0, "null cannot be cast to non-null type T of com.instagram.store.PendingActionStore"
        invoke-static { v1, v0 }, LX/04Zi;->A0W(Ljava/lang/Object;Ljava/lang/String;)V
        invoke-interface { v2, p1, v1 }, Ljava/util/Map;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
        monitor-exit v3
        const/4 v2, 0x1
        return v2
        move-exception v0
        monitor-exit v3
        throw v0
    """, protected = 4 to 16)
    method("A0C", listOf("Ljava/lang/String;"), "V", 4, """
        iget-object v1, p0, $lock
        monitor-enter v1
        iget-object v0, p0, $flight
        invoke-interface { v0, p1 }, Ljava/util/Map;->remove(Ljava/lang/Object;)Ljava/lang/Object;
        monitor-exit v1
        return-void
        move-exception v0
        monitor-exit v1
        throw v0
    """, protected = 2 to 4)
    method("A0I", emptyList(), "V", 10, """
        move-object v6, p0
        monitor-enter v6
        invoke-virtual { p0 }, $owner->A03()I
        invoke-virtual { p0 }, $owner->A05()Ljava/util/ArrayList;
        move-result-object v0
        invoke-virtual { v0 }, Ljava/util/AbstractCollection;->iterator()Ljava/util/Iterator;
        move-result-object v2
        invoke-static { v2 }, LX/04Zi;->A0C(Ljava/lang/Object;)V
        :next
        invoke-interface { v2 }, Ljava/util/Iterator;->hasNext()Z
        move-result v0
        if-eqz v0, :done
        invoke-interface { v2 }, Ljava/util/Iterator;->next()Ljava/lang/Object;
        move-result-object v7
        check-cast v7, Ljava/lang/String;
        invoke-virtual { p0, v7 }, $owner->A04(Ljava/lang/String;)Ljava/lang/Object;
        move-result-object v5
        if-eqz v5, :next
        invoke-virtual { p0, v7 }, $owner->A0G(Ljava/lang/String;)Z
        move-result v0
        if-eqz v0, :next
        invoke-virtual { p0, v5 }, $owner->A0J(Ljava/lang/Object;)$request
        move-result-object v1
        invoke-virtual { p0 }, $owner->A0K()Ljava/lang/Integer;
        move-result-object v4
        const/4 v8, 0x0
        new-instance v3, Lfixture/RetryCallback;
        invoke-direct/range { v3 .. v8 }, Lfixture/RetryCallback;-><init>(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;I)V
        invoke-virtual { v1, v3 }, $request->callback(Lfixture/RetryCallback;)V
        invoke-virtual { p0 }, $owner->A0H()$USER_SESSION
        move-result-object v0
        invoke-static { v0 }, Lfixture/Scheduler;->forAccount($USER_SESSION)Lfixture/Scheduler;
        move-result-object v0
        invoke-virtual { v0, v1 }, Lfixture/Scheduler;->send($request)V
        goto :next
        :done
        monitor-exit v6
        return-void
        move-exception v0
        monitor-exit v6
        throw v0
    """, monitor = true, protected = 2 to 34)
    val fields = listOf("lock" to "Ljava/lang/Object;", "pending" to "Ljava/util/LinkedHashMap;",
        "flight" to "Ljava/util/Map;", "account" to USER_SESSION).map { (name, type) ->
        ImmutableField(owner, name, type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)
    }
    return ImmutableClassDef(owner, AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, "Ljava/lang/Object;",
        emptyList(), null, null, fields, methods)
}

/** 452's disk reader: it keys its read and its cleanup with the store's private key helper ([storyQueueKey]). */
internal fun storyQueueReader(store: String, owner: String): Method = storyQueueMethod(store, "A0L", emptyList(), "V", 10, """
    const-string v0, "PendingReelSeenStateStore.deserializeFromDisk"
    iget-object v5, p0, $store->storage:LX/00CN;
    invoke-direct { p0 }, $store->A01()Ljava/lang/String;
    move-result-object v1
    const/4 v0, 0x1
    invoke-virtual { v5, v1, v0 }, LX/00CN;->A02(Ljava/lang/String;Z)Ljava/lang/Object;
    move-result-object v0
    invoke-virtual { p0 }, $owner->A0I()V
    invoke-direct { p0 }, $store->A01()Ljava/lang/String;
    move-result-object v0
    invoke-virtual { v5, v0 }, LX/00CN;->A05(Ljava/lang/String;)V
    return-void
""")

/** 452's private key helper: the pending-story prefix followed by the store's own account's user ID. */
internal fun storyQueueKey(store: String, owner: String): Method = storyQueueMethod(store, "A01", emptyList(), "Ljava/lang/String;", 4, """
    invoke-virtual { p0 }, $owner->A0H()$USER_SESSION
    move-result-object v0
    iget-object v2, v0, $USER_SESSION->userId:Ljava/lang/String;
    const-string v1, "pending_reel_seen_states_"
    new-instance v0, Ljava/lang/StringBuilder;
    invoke-direct { v0, v1 }, Ljava/lang/StringBuilder;-><init>(Ljava/lang/String;)V
    invoke-virtual { v0, v2 }, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
    invoke-virtual { v0 }, Ljava/lang/Object;->toString()Ljava/lang/String;
    move-result-object v0
    return-object v0
""", private = true)

internal fun storyQueueMethod(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, body: String,
    constructor: Boolean = false, monitor: Boolean = false, protected: Pair<Int, Int>? = null, static: Boolean = false,
    cleanup: Boolean = false, private: Boolean = false): ImmutableMethod {
    val flags = (if (private) AccessFlags.PRIVATE.value else AccessFlags.PUBLIC.value) or
        (if (constructor) AccessFlags.CONSTRUCTOR.value else AccessFlags.FINAL.value) or
        (if (monitor) AccessFlags.DECLARED_SYNCHRONIZED.value else 0) or (if (static) AccessFlags.STATIC.value else 0)
    val method = MutableMethod(ImmutableMethod(owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
        flags, null, null, ImmutableMethodImplementation(registers, emptyList(), null, null)))
    method.addInstructionsWithLabels(0, body.trimIndent())
    val code = method.implementation!!.instructions.toList()
    val addresses = code.runningFold(0) { address, instruction -> address + instruction.codeUnits }
    val blocks = protected?.let { (start, end) -> listOf(ImmutableTryBlock(addresses[start], addresses[end] - addresses[start],
        listOf(ImmutableExceptionHandler(null, addresses[code.size - 3])))) } ?: if (cleanup) {
        listOf(ImmutableTryBlock(0, addresses[code.lastIndex], listOf(ImmutableExceptionHandler("Ljava/lang/IllegalStateException;", addresses[code.lastIndex]))))
    } else emptyList()
    return ImmutableMethod(owner, name, method.parameters, returns, flags, null, null,
        ImmutableMethodImplementation(registers, code, blocks, null))
}

/** The real native helper bodies, with stand-in owners for the backend and executor. */
internal fun storyQueueDiskHelpers(): List<ImmutableClassDef> {
    val disk = "LX/00CN;"
    val task = "LX/03jG;"
    val backend = "Lfixture/SeenBackend;"
    val executor = "Lfixture/SeenExecutor;"
    val parent = "Lfixture/BaseTask;"
    fun field(owner: String, name: String, type: String) = ImmutableField(owner, name, type,
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)
    fun type(owner: String, methods: List<Method>, fields: List<ImmutableField> = emptyList(), superclass: String = "Ljava/lang/Object;") =
        ImmutableClassDef(owner, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, superclass, null, null, null, fields, methods)
    val parameterCheck = """
        if-nez p0, :done
        invoke-static { p1 }, Ljava/lang/Integer;->toString(I)Ljava/lang/String;
        move-result-object v2
        new-instance v1, Ljava/lang/StringBuilder;
        invoke-direct { v1 }, Ljava/lang/StringBuilder;-><init>()V
        const-string v0, "param at index = "
        invoke-virtual { v1, v0 }, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
        invoke-virtual { v1, v2 }, Ljava/lang/StringBuilder;->append(Ljava/lang/String;)Ljava/lang/StringBuilder;
        invoke-virtual { v1 }, Ljava/lang/Object;->toString()Ljava/lang/String;
        move-result-object v0
        invoke-static { v0 }, LX/04Zi;->A05(Ljava/lang/String;)Ljava/lang/String;
        move-result-object v1
        new-instance v0, Ljava/lang/NullPointerException;
        invoke-direct { v0, v1 }, Ljava/lang/NullPointerException;-><init>(Ljava/lang/String;)V
        invoke-static { v0 }, LX/04Zi;->A0b(Ljava/lang/Throwable;)V
        throw v0
        :done
        return-void
    """
    return listOf(
        type("LX/04Zi;", listOf(
            storyQueueMethod("LX/04Zi;", "A0R", listOf("Ljava/lang/Object;"), "V", 2, """
                const/4 v0, 0x0
                invoke-static { p0, v0 }, LX/04Zi;->A0V(Ljava/lang/Object;I)V
                return-void
            """, static = true),
            storyQueueMethod("LX/04Zi;", "A0C", listOf("Ljava/lang/Object;"), "V", 2, """
                if-nez p0, :done
                const-string v0, "INVOKE_RETURN"
                invoke-static { p0, v0 }, LX/04Zi;->A0X(Ljava/lang/Object;Ljava/lang/String;)V
                invoke-static { }, LX/0002;->createAndThrow()LX/0002;
                move-result-object v0
                throw v0
                :done
                return-void
            """, static = true),
            storyQueueMethod("LX/04Zi;", "A0V", listOf("Ljava/lang/Object;", "I"), "V", 5, parameterCheck, static = true),
            storyQueueMethod("LX/04Zi;", "A0U", listOf("Ljava/lang/Object;", "I"), "V", 5,
                parameterCheck.replace("NullPointerException", "IllegalArgumentException"), static = true),
            storyQueueMethod("LX/04Zi;", "A05", listOf("Ljava/lang/String;"), "Ljava/lang/String;", 1, "return-object p0", static = true),
            storyQueueMethod("LX/04Zi;", "A0b", listOf("Ljava/lang/Throwable;"), "V", 1, "return-void", static = true),
            storyQueueMethod("LX/04Zi;", "A0W", listOf("Ljava/lang/Object;", "Ljava/lang/String;"), "V", 2, """
                if-nez p0, :done
                new-instance p0, Ljava/lang/NullPointerException;
                invoke-direct { p0, p1 }, Ljava/lang/NullPointerException;-><init>(Ljava/lang/String;)V
                invoke-static { p0 }, LX/04Zi;->A0b(Ljava/lang/Throwable;)V
                throw p0
                :done
                return-void
            """, static = true))),
        type(disk, listOf(
            storyQueueMethod(disk, "A02", listOf("Ljava/lang/String;", "Z"), "Ljava/lang/Object;", 4, """
                const/4 v0, 0x0
                return-object v0
            """),
            storyQueueMethod(disk, "A00", listOf(disk), backend, 2, """
                iget-object v0, p0, $disk->backend:$backend
                return-object v0
            """, static = true),
            storyQueueMethod(disk, "A05", listOf("Ljava/lang/String;"), "V", 4, """
                const/4 v0, 0x0
                invoke-static { p1, v0 }, LX/04Zi;->A0V(Ljava/lang/Object;I)V
                iget-object v1, p0, $disk->executor:$executor
                new-instance v0, $task
                invoke-direct { v0, p0, p1 }, $task-><init>($disk${"Ljava/lang/String;"})V
                invoke-virtual { v1, v0 }, $executor->submit($parent)V
                return-void
            """)), listOf(field(disk, "backend", backend), field(disk, "executor", executor))),
        type(task, listOf(
            storyQueueMethod(task, "<init>", listOf(disk, "Ljava/lang/String;"), "V", 6, """
                iput-object p1, p0, $task->storage:$disk
                const/16 v2, 0x1fd
                const/4 v1, 0x3
                const/4 v0, 0x0
                invoke-direct { p0, v2, v1, v0, v0 }, $parent-><init>(IIZZ)V
                iput-object p2, p0, $task->key:Ljava/lang/String;
                return-void
            """, constructor = true),
            storyQueueMethod(task, "run", emptyList(), "V", 4, """
                iget-object v0, p0, $task->storage:$disk
                invoke-static { v0 }, $disk->A00($disk)$backend
                move-result-object v2
                iget-object v1, p0, $task->key:Ljava/lang/String;
                const/4 v0, 0x0
                invoke-interface { v2, v1, v0 }, $backend->remove(Ljava/lang/String;Ljava/util/Map;)V
                return-void
            """, cleanup = true)), listOf(field(task, "storage", disk), field(task, "key", "Ljava/lang/String;")), parent),
        ImmutableClassDef(backend, AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value, "Ljava/lang/Object;",
            null, null, null, null, listOf(ImmutableMethod(backend, "remove", listOf("Ljava/lang/String;", "Ljava/util/Map;")
                .map { ImmutableMethodParameter(it, null, null) }, "V", AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null))),
        type(executor, listOf(storyQueueMethod(executor, "submit", listOf(parent), "V", 2, "return-void"))),
    )
}

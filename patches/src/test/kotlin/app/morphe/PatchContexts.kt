/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.morphe

import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.PatcherContext
import app.morphe.patcher.patch.BytecodePatchContext
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import java.io.File

/**
 * A patcher context over a handful of classes and no APK, so a test can run a patch's execute block
 * as the patcher does and read what it left in each class.
 *
 * The patcher fills its class pool from an APK's dex files, and what takes a pool directly is
 * internal to it: the context's constructor, the pool's class and its setter. They are reached by
 * their JVM names, pinned to morphe-patcher 2.3.0. A bump that renames one fails here, naming it,
 * rather than leaving a test that never ran the patch. Nothing is written to disk: the context only
 * names its work folders until something compiles the dex, which no test here asks for.
 *
 * A fingerprint finds its classes through the patcher's one current run, not through a context it's
 * handed, so the newest context from [of] is what every fingerprint searches. A test holding two
 * contexts calls [use] before going back to the older one.
 */
internal object PatchContexts {
    fun of(classes: Collection<ClassDef>): BytecodePatchContext {
        val work = File(System.getProperty("java.io.tmpdir"), "hushgram-patch-context")
        val config = PatcherConfig(apkFile = File(work, "none.apk"), temporaryFilesPath = work)
        val metadataType = Class.forName("app.morphe.patcher.PackageMetadata")
        val metadata = metadataType.getConstructor(
            String::class.java, String::class.java, String::class.java,
            Class.forName("com.reandroid.archive.block.ApkSignatureBlock"),
        ).newInstance("com.instagram.android", "0", "0", null)
        val context = BytecodePatchContext::class.java.getConstructor(PatcherConfig::class.java, metadataType)
            .newInstance(config, metadata)
        val poolType = Class.forName("app.morphe.patcher.util.PatchClasses")
        val progressType = Class.forName("app.morphe.patcher.PatcherProgress")
        val pool = poolType.getConstructor(Set::class.java, progressType).newInstance(classes.toSet(), null)
        BytecodePatchContext::class.java.getMethod("setPatchClasses\$morphe_patcher", poolType).invoke(context, pool)
        BytecodePatchContext::class.java.getMethod("setOpcodes\$morphe_patcher", Opcodes::class.java)
            .invoke(context, Opcodes.getDefault())
        use(context)
        return context
    }

    /**
     * Makes [context] the patcher's current run. A real run builds its own from an APK, which these
     * contexts don't have, so this one is allocated with only the field a fingerprint reads.
     */
    fun use(context: BytecodePatchContext) {
        val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val run = unsafe.javaClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, PatcherContext::class.java)
        PatcherContext::class.java.getDeclaredField("bytecodeContext").apply { isAccessible = true }.set(run, context)
        PatcherContext::class.java.getDeclaredField("current").apply { isAccessible = true }.set(null, run)
    }

    /** Adds [classDef] to the context's pool as the patcher merges an extension's class, replacing one of its type. */
    fun add(context: BytecodePatchContext, classDef: ClassDef) {
        val pool = BytecodePatchContext::class.java.getMethod("getPatchClasses\$morphe_patcher").invoke(context)
        pool.javaClass.getMethod("addClass\$morphe_patcher", ClassDef::class.java).invoke(pool, classDef)
    }
}

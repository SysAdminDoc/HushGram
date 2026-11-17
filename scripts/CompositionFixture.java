/* Copyright (C) 2026 HushGram. GPL-3.0-only. Test-only native composition fixtures.
 * https://github.com/SysAdminDoc/HushGram
 */
import app.morphe.patcher.patch.AppTarget;
import app.morphe.patcher.patch.ApkFileType;
import app.morphe.patcher.patch.BytecodePatchContext;
import app.morphe.patcher.patch.Compatibility;
import app.morphe.patcher.patch.FormFactor;
import app.morphe.patcher.patch.Patch;
import app.morphe.patcher.patch.PatchKt;
import app.morphe.patcher.patch.SupportedAbi;
import com.android.tools.smali.dexlib2.Opcode;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21c;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction;
import com.android.tools.smali.dexlib2.iface.reference.StringReference;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x;
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c;
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import kotlin.Unit;

public final class CompositionFixture {
    private static final String MODE = mode();
    private static final String TYPE = "Lapp/hushgram/fixture/" +
            (Set.of("base", "conflict", "identical", "identical-debug").contains(MODE) ? "base" : "addon") + "/Bridge;";
    private static final Compatibility TARGET = target(
            MODE.equals("wrong-version") ? "439.0.0.37.89" : "450.0.0.50.77",
            MODE.equals("wrong-code") ? 385611437 : 385611438);

    private static Compatibility target(String version, int code) {
        return new Compatibility("com.instagram.android", "Instagram fixture", null, ApkFileType.APK, (Integer) null, null,
                List.of(new AppTarget(version, Map.of(SupportedAbi.ARM64_V8A, code), false, null, null)), FormFactor.MOBILE);
    }

    public static final Patch SETTINGS = PatchKt.bytecodePatch("Fixture settings", "Native initializer", false, builder -> {
        builder.compatibleWith(MODE.equals("dependency-target") ? target("439.0.0.37.89", 384510827) : TARGET);
        builder.extendWith(() -> CompositionFixture.class.getResourceAsStream("/extensions/fixture.mpe"));
        builder.execute(context -> {
            // This must run in the native patcher after extension merge, never in inspection.
            context.classDefBy(TYPE);
            if (MODE.equals("failure")) {
                try {
                    Class.forName("CompositionInitializer", true, CompositionFixture.class.getClassLoader())
                            .getMethod("fail").invoke(null);
                } catch (ReflectiveOperationException error) { throw new IllegalStateException("Native dependency failed", error); }
            }
            mark(context, "initialized after merge");
            return Unit.INSTANCE;
        });
        builder.finalize(context -> { mark(context, "finalized after merge"); return Unit.INSTANCE; });
        return Unit.INSTANCE;
    });

    public static final Patch SELECTED = PatchKt.bytecodePatch(
            MODE.equals("base") ? "Fixture base" : "Compatible addon", "Source composition fixture", false, builder -> {
        builder.compatibleWith(TARGET);
        builder.dependsOn(SETTINGS);
        builder.execute(context -> {
            ClassDef type = context.classDefBy(TYPE);
            String value = ((StringReference) ((ReferenceInstruction) type.getMethods().iterator().next()
                    .getImplementation().getInstructions().iterator().next()).getReference()).getString();
            if (!value.equals("initialized after merge")) throw new IllegalStateException("Initializer was skipped");
            return Unit.INSTANCE;
        });
        return Unit.INSTANCE;
    });

    public static Patch getUnused() {
        if (!MODE.equals("mixed")) return SETTINGS;
        return PatchKt.bytecodePatch("Unselected old patch", "Deliberately incompatible", false, builder -> {
            builder.compatibleWith(target("439.0.0.37.89", 384510827));
            builder.extendWith(() -> CompositionFixture.class.getResourceAsStream("/extensions/unused.mpe"));
            builder.execute(context -> { throw new IllegalStateException("Unselected patch executed"); });
            return Unit.INSTANCE;
        });
    }

    private static String mode() {
        try (InputStream input = CompositionFixture.class.getResourceAsStream("/fixture-mode.txt")) {
            return input == null ? "base" : new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static void mark(BytecodePatchContext context, String value) {
        context.mutableClassDefBy(TYPE).getMethods().iterator().next().getImplementation().replaceInstruction(0,
                new BuilderInstruction21c(Opcode.CONST_STRING, 0, new ImmutableStringReference(value)));
    }

    private static byte[] dex(String type, String value) throws Exception {
        return dex(type, value, null);
    }

    private static byte[] dex(String type, String value, String parameterName) throws Exception {
        var implementation = new ImmutableMethodImplementation(1, List.of(
                new ImmutableInstruction21c(Opcode.CONST_STRING, 0, new ImmutableStringReference(value)),
                new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)), List.of(), List.of());
        var parameters = List.of(new ImmutableMethodParameter("I", Set.of(), parameterName));
        var method = new ImmutableMethod(type, "state", parameters, "Ljava/lang/String;", 9, Set.of(), Set.of(), implementation);
        var definition = new ImmutableClassDef(type, 1, "Ljava/lang/Object;", List.of(), null,
                Set.of(), List.of(), List.of(method));
        MemoryDataStore store = new MemoryDataStore();
        try {
            DexPool pool = new DexPool(Opcodes.getDefault()); pool.internClass(definition); pool.writeTo(store);
            return store.getData();
        } finally { store.close(); }
    }

    private static void add(JarOutputStream jar, String name, byte[] bytes) throws Exception {
        jar.putNextEntry(new ZipEntry(name)); jar.write(bytes); jar.closeEntry();
    }

    private static void bundle(Path classes, Path output, String mode) throws Exception {
        Manifest manifest = new Manifest(); Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0"); attributes.putValue("Name", "Fixture " + mode);
        attributes.putValue("Version", "1.0"); attributes.putValue("Patcher-Version", "1.14.1");
        String type = "Lapp/hushgram/fixture/" +
                (Set.of("base", "conflict", "identical", "identical-debug").contains(mode) ? "base" : "addon") + "/Bridge;";
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(output), manifest)) {
            add(jar, "CompositionFixture.class", Files.readAllBytes(classes.resolve("CompositionFixture.class")));
            if (mode.equals("failure")) add(jar, "CompositionInitializer.class",
                    Files.readAllBytes(classes.resolve("CompositionInitializer.class")));
            add(jar, "fixture-mode.txt", mode.getBytes(StandardCharsets.UTF_8));
            add(jar, "extensions/fixture.mpe", dex(type, mode.equals("conflict") ? "conflicting definition" : "uninitialized",
                    mode.equals("identical-debug") ? "differentDebugParameter" : null));
            if (mode.equals("mixed")) add(jar, "extensions/unused.mpe",
                    dex("Lapp/hushgram/fixture/base/Bridge;", "unselected conflicting definition"));
        }
    }

    private static void assertPatched(Path apk, String... types) throws Exception {
        try (ZipFile zip = new ZipFile(apk.toFile())) {
            Set<String> pending = new java.util.HashSet<>(List.of(types));
            for (var entry : java.util.Collections.list(zip.entries())) {
                if (!entry.getName().matches("classes[0-9]*\\.dex")) continue;
                try (InputStream input = zip.getInputStream(entry)) {
                    var dex = new DexBackedDexFile(Opcodes.getDefault(), ByteBuffer.wrap(input.readAllBytes()));
                    for (ClassDef type : dex.getClasses()) {
                        if (!pending.remove(type.getType())) continue;
                        String value = ((StringReference) ((ReferenceInstruction) type.getMethods().iterator().next()
                                .getImplementation().getInstructions().iterator().next()).getReference()).getString();
                        String expected = type.getType().equals("Lapp/hushgram/fixture/Stock;")
                                ? "stock preserved" : "finalized after merge";
                        if (!value.equals(expected)) throw new IllegalStateException("Missing finalizer or changed stock method");
                    }
                }
            }
            if (!pending.isEmpty()) throw new IllegalStateException("Missing merged addon definitions");
        }
    }

    public static void main(String[] args) throws Exception {
        if (args[0].equals("assert")) {
            assertPatched(Path.of(args[1]), java.util.Arrays.copyOfRange(args, 2, args.length));
            System.out.println("Native extension merge, dependency execute and finalizer verified");
            return;
        }
        Path classes = Path.of(args[0]); Path output = Path.of(args[1]); Files.createDirectories(output);
        for (String mode : List.of("base", "addon", "wrong-version", "wrong-code", "conflict", "identical",
                "identical-debug", "failure", "dependency-target", "mixed")) {
            bundle(classes, output.resolve(mode + ".mpp"), mode);
        }
        Files.write(output.resolve("stock.dex"), dex("Lapp/hushgram/fixture/Stock;", "stock preserved"));
    }
}

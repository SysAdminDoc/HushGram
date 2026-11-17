/* Copyright (C) 2026 HushGram. GPL-3.0-only.
 * https://github.com/SysAdminDoc/HushGram
 */
import app.morphe.patcher.patch.AppTarget;
import app.morphe.patcher.patch.Compatibility;
import app.morphe.patcher.patch.Patch;
import app.morphe.patcher.patch.PatchLoader;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.Method;
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation;
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter;
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/** Reads one isolated bundle. Patch execute/finalize blocks are never called here. */
public final class InspectPatchBundle {
    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Map<String, Object> entry(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    private static String text(String value) {
        if (value == null) return "";
        return value.replaceAll("[\\p{Cntrl}]", "");
    }

    private static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?, ?> map) {
            List<String> items = new ArrayList<>();
            map.forEach((key, item) -> items.add(json(key.toString()) + ":" + json(item)));
            return "{" + String.join(",", items) + "}";
        }
        if (value instanceof Iterable<?> items) {
            List<String> encoded = new ArrayList<>();
            items.forEach(item -> encoded.add(json(item)));
            return "[" + String.join(",", encoded) + "]";
        }
        StringBuilder result = new StringBuilder("\"");
        for (char ch : value.toString().toCharArray()) {
            if (ch == '\\' || ch == '"') result.append('\\').append(ch);
            else if (ch < 32) result.append(String.format("\\u%04x", (int) ch));
            else result.append(ch);
        }
        return result.append('"').toString();
    }

    private static boolean forPackage(Patch patch, String packageName) {
        return patch.getCompatibility() == null || patch.getCompatibility().stream()
                .anyMatch(target -> target.getPackageName() == null || target.getPackageName().equals(packageName));
    }

    private static List<Map<String, Object>> compatibility(Patch patch) {
        if (patch.getCompatibility() == null) return null;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Compatibility compatible : patch.getCompatibility()) {
            List<Map<String, Object>> targets = new ArrayList<>();
            for (AppTarget target : compatible.getTargets()) {
                Map<String, Object> codes = new LinkedHashMap<>();
                if (target.getVersionCodes() != null) target.getVersionCodes().forEach(
                        (abi, code) -> codes.put(abi.toString(), code.toString()));
                targets.add(entry("version", target.getVersion(), "versionCodes", codes,
                        "experimental", target.isExperimental(), "minSdk", target.getMinSdk()));
            }
            result.add(entry("packageName", compatible.getPackageName(), "targets", targets,
                    "signatures", compatible.getSignatures(), "apkFileType",
                    compatible.getApkFileType() == null ? null : compatible.getApkFileType().toString()));
        }
        return result;
    }

    // Separate per-class pools remove unrelated DEX pool indices. Debug lines/source paths are
    // not definitions, while annotations, instructions, exception handlers and fields are.
    private static String definitionHash(ClassDef type) throws Exception {
        List<Method> methods = new ArrayList<>();
        for (Method method : type.getMethods()) {
            var original = method.getImplementation();
            var implementation = original == null ? null : new ImmutableMethodImplementation(
                    original.getRegisterCount(), original.getInstructions(), original.getTryBlocks(), List.of());
            var parameters = method.getParameters().stream().map(parameter -> new ImmutableMethodParameter(
                    parameter.getType(), parameter.getAnnotations(), null)).toList();
            methods.add(new ImmutableMethod(method.getDefiningClass(), method.getName(), parameters,
                    method.getReturnType(), method.getAccessFlags(), method.getAnnotations(),
                    method.getHiddenApiRestrictions(), implementation));
        }
        ClassDef definition = new ImmutableClassDef(type.getType(), type.getAccessFlags(), type.getSuperclass(),
                type.getInterfaces(), null, type.getAnnotations(), type.getFields(), methods);
        MemoryDataStore store = new MemoryDataStore();
        try {
            DexPool pool = new DexPool(Opcodes.getDefault());
            pool.internClass(definition);
            pool.writeTo(store);
            return sha(store.getData());
        } finally { store.close(); }
    }

    private static Map<String, Object> header(Path bundle) throws Exception {
        String digest = sha(Files.readAllBytes(bundle));
        List<String> classes;
        Map<String, Object> identity;
        try (JarFile jar = new JarFile(bundle.toFile())) {
            if (jar.getManifest() == null) throw new IllegalArgumentException("Missing bundle manifest");
            var attributes = jar.getManifest().getMainAttributes();
            identity = entry("sha256", digest, "name", text(attributes.getValue("Name")),
                    "version", text(attributes.getValue("Version")),
                    "patcherVersion", text(attributes.getValue("Patcher-Version")),
                    "buildIdentity", text(attributes.getValue("HushGram-Build-Identity")));
            classes = jar.stream().map(item -> item.getName())
                    .filter(name -> name.endsWith(".class") && !name.startsWith("META-INF/versions/"))
                    .map(name -> name.substring(0, name.length() - 6).replace('/', '.')).sorted().toList();
        }
        return entry("identity", identity, "classes", classes);
    }

    private static Map<String, Object> inspect(Path bundle, String packageName, Path selection) throws Exception {
        List<String> requested = Files.readAllLines(selection);
        if (requested.isEmpty() || requested.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Missing patch selection");
        }
        Map<String, Object> header = header(bundle);
        PatchLoader loader = new PatchLoader.Jar(Set.of(bundle.toFile()));
        List<Patch> named = loader.stream().filter(patch -> forPackage(patch, packageName))
                .sorted(Comparator.comparing(Patch::getName)).toList();
        List<Patch> selected = new ArrayList<>();
        if (requested.equals(List.of("*"))) selected.addAll(named);
        else for (String name : requested) {
            List<Patch> matches = named.stream().filter(patch -> name.equals(patch.getName())).toList();
            if (matches.size() != 1) throw new IllegalArgumentException("Missing or ambiguous selected patch");
            if (!selected.contains(matches.getFirst())) selected.add(matches.getFirst());
        }
        if (selected.isEmpty()) throw new IllegalArgumentException("No patches selected for this package");
        Field execute = Patch.class.getDeclaredField("executeBlock");
        execute.setAccessible(true);
        Map<Patch, String> ids = new IdentityHashMap<>();
        ArrayDeque<Patch> pending = new ArrayDeque<>();
        for (Patch patch : selected) {
            if (!ids.containsKey(patch)) { ids.put(patch, "p" + ids.size()); pending.add(patch); }
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        List<Map<String, Object>> extensions = new ArrayList<>();
        // Inspect selected dependency closure, including unnamed initializers. Reading extension
        // streams is distinct from calling execute/finalize after the patcher merges those streams.
        while (!pending.isEmpty()) {
            Patch patch = pending.remove();
            List<String> dependencies = new ArrayList<>();
            for (Patch dependency : patch.getDependencies()) {
                if (!ids.containsKey(dependency)) {
                    ids.put(dependency, "p" + ids.size()); pending.add(dependency);
                }
                dependencies.add(ids.get(dependency));
            }
            Object block = execute.get(patch);
            String implementation = block == null ? "" : block.getClass().getNestHost().getName();
            nodes.add(entry("id", ids.get(patch), "name", patch.getName(), "implementation", implementation,
                    "selected", selected.contains(patch), "compatibility", compatibility(patch),
                    "dependencies", dependencies));
            // Patcher 2.4 keeps a patch's extensions on Patch itself; a patch without any answers none.
            for (var provider : patch.getExtensionStreamProviders$morphe_patcher()) {
                for (var stream : provider.get()) {
                    try (InputStream input = stream.get()) {
                        if (input == null) throw new IllegalArgumentException("Missing selected extension");
                        var dex = new DexBackedDexFile(Opcodes.getDefault(), ByteBuffer.wrap(input.readAllBytes()));
                        for (ClassDef type : dex.getClasses()) extensions.add(entry("type", type.getType(),
                                "sha256", definitionHash(type), "patch", ids.get(patch)));
                    }
                }
            }
        }
        return entry("schemaVersion", 1, "identity", header.get("identity"), "classes", header.get("classes"), "patches", nodes,
                "extensions", extensions);
    }

    public static void main(String[] args) {
        try {
            if (args.length != 3) throw new IllegalArgumentException("Expected bundle, package and selection");
            System.out.println(json(inspect(Path.of(args[0]), args[1], Path.of(args[2]))));
        } catch (Exception | LinkageError error) {
            // Don't copy arbitrary bundle exception text or private input paths into a report.
            try {
                Map<String, Object> header = header(Path.of(args[0]));
                System.out.println(json(entry("schemaVersion", 1, "identity", header.get("identity"),
                        "classes", header.get("classes"), "patches", List.of(), "extensions", List.of(),
                        "inspectionError", error.getClass().getSimpleName())));
            } catch (Exception unreadable) {
                System.err.println("Bundle inspection failed (" + error.getClass().getSimpleName() + ")");
            }
            System.exit(2);
        }
    }
}

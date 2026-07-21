import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class AbiVerifier {
    record Member(String owner, String name, String descriptor) {
        @Override public String toString() { return owner.replace('/', '.') + "." + name + descriptor; }
    }
    static final class Info {
        String superName;
        List<String> interfaces = new ArrayList<>();
        Set<String> methods = new LinkedHashSet<>();
        Set<String> fields = new LinkedHashSet<>();
    }

    private final Map<String, Info> classes = new HashMap<>();
    private final Set<Member> methodReferences = new LinkedHashSet<>();
    private final Set<Member> fieldReferences = new LinkedHashSet<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Usage: AbiVerifier plugin.jar api.jar...");
        AbiVerifier verifier = new AbiVerifier();
        for (String arg : args) verifier.index(Path.of(arg));
        verifier.collectReferences(Path.of(args[0]));
        List<String> errors = verifier.verify();
        if (!errors.isEmpty()) {
            errors.forEach(System.err::println);
            throw new AssertionError("ABI errors: " + errors.size());
        }
        System.out.println("ABI OK: " + verifier.methodReferences.size() + " method refs, " + verifier.fieldReferences.size() + " field refs");
    }

    private void index(Path path) throws Exception {
        try (JarFile jar = new JarFile(path.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class") || entry.getName().startsWith("META-INF/versions/")) continue;
                try (InputStream input = jar.getInputStream(entry)) {
                    new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                        Info info;
                        @Override public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                            info = classes.computeIfAbsent(name, ignored -> new Info());
                            info.superName = superName;
                            info.interfaces = List.of(interfaces);
                        }
                        @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                            info.methods.add(name + descriptor);
                            return null;
                        }
                        @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                            info.fields.add(name + ":" + descriptor);
                            return null;
                        }
                    }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
        }
    }

    private void collectReferences(Path plugin) throws Exception {
        try (JarFile jar = new JarFile(plugin.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) continue;
                try (InputStream input = jar.getInputStream(entry)) {
                    new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                        @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                            return new MethodVisitor(Opcodes.ASM9) {
                                @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
                                    if (external(owner)) methodReferences.add(new Member(owner, name, descriptor));
                                }
                                @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                                    if (external(owner)) fieldReferences.add(new Member(owner, name, descriptor));
                                }
                            };
                        }
                    }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                }
            }
        }
    }

    private boolean external(String owner) {
        return owner.startsWith("org/bukkit/") || owner.startsWith("net/md_5/bungee/") || owner.startsWith("net/milkbowl/vault/");
    }

    private List<String> verify() {
        List<String> errors = new ArrayList<>();
        for (Member ref : methodReferences) if (!hasMethod(ref.owner, ref.name + ref.descriptor, new LinkedHashSet<>())) errors.add("MISSING METHOD " + ref);
        for (Member ref : fieldReferences) if (!hasField(ref.owner, ref.name + ":" + ref.descriptor, new LinkedHashSet<>())) errors.add("MISSING FIELD " + ref);
        return errors;
    }

    private boolean hasMethod(String owner, String signature, Set<String> seen) {
        if (!seen.add(owner)) return false;
        Info info = classes.get(owner);
        if (info == null) return false;
        if (info.methods.contains(signature)) return true;
        if ("java/lang/Enum".equals(info.superName) && (signature.equals("name()Ljava/lang/String;") || signature.equals("ordinal()I") || signature.equals("toString()Ljava/lang/String;"))) return true;
        if ("java/lang/Object".equals(info.superName) && (signature.equals("toString()Ljava/lang/String;") || signature.equals("hashCode()I") || signature.equals("equals(Ljava/lang/Object;)Z"))) return true;
        if (info.superName != null && hasMethod(info.superName, signature, seen)) return true;
        for (String parent : info.interfaces) if (hasMethod(parent, signature, seen)) return true;
        return false;
    }

    private boolean hasField(String owner, String signature, Set<String> seen) {
        if (!seen.add(owner)) return false;
        Info info = classes.get(owner);
        if (info == null) return false;
        if (info.fields.contains(signature)) return true;
        if (info.superName != null && hasField(info.superName, signature, seen)) return true;
        for (String parent : info.interfaces) if (hasField(parent, signature, seen)) return true;
        return false;
    }
}

package dev.donut.addon;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Checks compiled addon references against the LiquidBounce on the test runtime classpath. */
class LiquidBounceLinkageTest {
    private static final String PREFIX = "net/ccbluex/liquidbounce/";
    private final Map<String, Optional<ClassNode>> classes = new HashMap<>();
    private final Set<String> errors = new TreeSet<>();

    @Test void liquidBounceReferencesResolve() throws Exception {
        String directories = System.getProperty("donut.mainClassDirectories");
        assertNotNull(directories, "Set donut.mainClassDirectories to the main output class directories");
        int count = 0;
        for (String directory : directories.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (directory.isBlank() || !Files.isDirectory(Path.of(directory))) continue;
            try (var paths = Files.walk(Path.of(directory))) {
                for (Path path : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                    ClassNode node = new ClassNode();
                    try (InputStream in = Files.newInputStream(path)) { new ClassReader(in).accept(node, 0); }
                    inspect(node);
                    count++;
                }
            }
        }
        assertTrue(count > 0, "No compiled main classes found in " + directories);
        assertTrue(errors.isEmpty(), () -> "Unresolved LiquidBounce bytecode references:\n" + String.join("\n", errors));
    }

    private void inspect(ClassNode node) {
        checkClass(node.superName, node.name);
        node.interfaces.forEach(name -> checkClass(name, node.name));
        for (FieldNode field : node.fields) checkType(Type.getType(field.desc), node.name + "." + field.name);
        for (MethodNode method : node.methods) {
            String location = node.name + "." + method.name + method.desc;
            checkType(Type.getMethodType(method.desc), location);
            if (method.exceptions != null) method.exceptions.forEach(name -> checkClass(name, location));
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    checkType(Type.getMethodType(call.desc), location);
                    if (call.owner.startsWith(PREFIX)) {
                        checkMember(call.owner, call.name, call.desc, true,
                            call.getOpcode() == Opcodes.INVOKESTATIC, location);
                    }
                } else if (instruction instanceof FieldInsnNode field) {
                    checkType(Type.getType(field.desc), location);
                    if (field.owner.startsWith(PREFIX)) {
                        checkMember(field.owner, field.name, field.desc, false,
                            field.getOpcode() == Opcodes.GETSTATIC || field.getOpcode() == Opcodes.PUTSTATIC, location);
                    }
                } else if (instruction instanceof TypeInsnNode type) {
                    if (type.desc.startsWith("[")) checkType(Type.getType(type.desc), location);
                    else checkClass(type.desc, location);
                } else if (instruction instanceof MultiANewArrayInsnNode array) {
                    checkType(Type.getType(array.desc), location);
                } else if (instruction instanceof LdcInsnNode constant && constant.cst instanceof Type type) {
                    checkType(type, location);
                }
            }
        }
    }

    private void checkType(Type type, String location) {
        switch (type.getSort()) {
            case Type.METHOD -> {
                for (Type argument : type.getArgumentTypes()) checkType(argument, location);
                checkType(type.getReturnType(), location);
            }
            case Type.ARRAY -> checkType(type.getElementType(), location);
            case Type.OBJECT -> checkClass(type.getInternalName(), location);
            default -> { }
        }
    }

    private void checkClass(String name, String location) {
        if (name != null && name.startsWith(PREFIX) && read(name).isEmpty())
            errors.add(location + ": missing class " + name);
    }

    private void checkMember(String owner, String name, String desc, boolean method, boolean isStatic, String location) {
        Optional<ClassNode> target = read(owner);
        if (target.isEmpty()) {
            errors.add(location + ": missing class " + owner);
            return;
        }
        Integer access = findMember(owner, name, desc, method, new HashSet<>());
        String symbol = owner + "." + name + desc;
        if (access == null) errors.add(location + ": missing " + symbol);
        else if (((access & Opcodes.ACC_STATIC) != 0) != isStatic)
            errors.add(location + ": static/instance mismatch for " + symbol);
    }

    private Integer findMember(String owner, String name, String desc, boolean method, Set<String> visited) {
        if (owner == null || !visited.add(owner)) return null;
        Optional<ClassNode> loaded = read(owner);
        if (loaded.isEmpty()) return null;
        ClassNode node = loaded.get();
        if (method) {
            for (MethodNode candidate : node.methods)
                if (candidate.name.equals(name) && candidate.desc.equals(desc)) return candidate.access;
        } else {
            for (FieldNode candidate : node.fields)
                if (candidate.name.equals(name) && candidate.desc.equals(desc)) return candidate.access;
        }
        // Constructors are never inherited.
        if (name.equals("<init>")) return null;
        Integer inherited = findMember(node.superName, name, desc, method, visited);
        if (inherited != null) return inherited;
        for (String parent : node.interfaces) {
            inherited = findMember(parent, name, desc, method, visited);
            if (inherited != null) return inherited;
        }
        return null;
    }

    private Optional<ClassNode> read(String name) {
        return classes.computeIfAbsent(name, key -> {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(key + ".class")) {
                if (in == null) return Optional.empty();
                ClassNode node = new ClassNode();
                new ClassReader(in).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                return Optional.of(node);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot read class " + key, e);
            }
        });
    }
}

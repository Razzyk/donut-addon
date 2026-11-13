package dev.donut.addon;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Check the shipped Mixin declarations against the actual dependency without initializing Minecraft. */
class NativeHookTargetsTest {
    @Test void reservedMixinPackagesContainNoDirectlyLoadedRuntimeHelpers() throws Exception {
        var declarations = new HashMap<String, Set<String>>();
        for (String configName : mixinConfigs()) {
            try (var resource = getClass().getResourceAsStream("/" + configName)) {
                assertNotNull(resource, configName);
                var config = JsonParser.parseReader(new InputStreamReader(resource)).getAsJsonObject();
                String prefix = config.get("package").getAsString().replace('.', '/') + "/";
                var registered = declarations.computeIfAbsent(prefix, ignored -> new HashSet<>());
                for (String side : List.of("mixins", "client", "server")) {
                    if (!config.has(side)) continue;
                    for (var entry : config.getAsJsonArray(side)) registered.add(prefix + entry.getAsString());
                }
            }
        }
        String directories = System.getProperty("donut.mainClassDirectories");
        assertNotNull(directories);
        var forbidden = new ArrayList<String>();
        int inspected = 0;
        for (String directory : directories.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            Path root = Path.of(directory);
            if (!Files.isDirectory(root)) continue;
            try (var paths = Files.walk(root)) {
                for (Path file : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                    String name = root.relativize(file).toString().replace(File.separatorChar, '/');
                    name = name.substring(0, name.length() - ".class".length());
                    for (var declaration : declarations.entrySet()) {
                        if (!name.startsWith(declaration.getKey())) continue;
                        inspected++;
                        String outer = name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
                        if (!declaration.getValue().contains(outer)) forbidden.add(name);
                    }
                }
            }
        }
        assertTrue(inspected > 0, "No compiled mixin classes were inspected");
        assertTrue(forbidden.isEmpty(), () -> "Mixin reserves these packages: move ordinary runtime helpers outside them: "
            + String.join(", ", forbidden));
    }

    @Test void brightnessOverrideTargetsARealStoredGammaLocal() throws Exception {
        var mixin = read("dev/donut/addon/expansion/render/mixin/MixinDonutLightmap");
        var handler = mixin.methods.stream().filter(m -> m.name.equals("donut$immediateGamma")).findFirst().orElseThrow();
        var declaration = annotation(annotations(handler), "ModifyVariable");
        assertNotNull(declaration);
        assertEquals(List.of("brightnessOption"), value(declaration, "name"));
        var target = read("net/minecraft/client/renderer/LightmapRenderStateExtractor");
        var extract = target.methods.stream().filter(m -> m.name.equals("extract")).findFirst().orElseThrow();
        assertNotNull(extract.localVariables);
        var gamma = extract.localVariables.stream()
            .filter(local -> local.name.equals("brightnessOption") && local.desc.equals("F"))
            .findFirst().orElseThrow();
        boolean stored = false;
        for (var instruction : extract.instructions) {
            if (instruction instanceof org.objectweb.asm.tree.VarInsnNode variable &&
                variable.getOpcode() == Opcodes.FSTORE && variable.var == gamma.index) stored = true;
        }
        assertTrue(stored, "native lightmap gamma must be a stored float local");
    }

    @Test void accessorInterfacesTargetingClassesHaveNoHelperMethods() throws Exception {
        for (String configName : mixinConfigs()) {
        try (var resource = getClass().getResourceAsStream("/" + configName)) {
            assertNotNull(resource);
            var config = JsonParser.parseReader(new InputStreamReader(resource)).getAsJsonObject();
            String basePackage = config.get("package").getAsString().replace('.', '/');
            for (var name : config.getAsJsonArray("client")) {
                var mixin = read(basePackage + "/" + name.getAsString());
                if ((mixin.access & Opcodes.ACC_INTERFACE) == 0) continue;
                var declaration = annotation(annotations(mixin), "Mixin");
                assertNotNull(declaration, mixin.name);
                for (var targetType : (List<?>) value(declaration, "value")) {
                    var target = read(((Type) targetType).getInternalName());
                    if ((target.access & Opcodes.ACC_INTERFACE) != 0) continue;
                    for (var method : mixin.methods) {
                        if ((method.access & Opcodes.ACC_SYNTHETIC) != 0) continue;
                        var methodAnnotations = annotations(method);
                        assertTrue(annotation(methodAnnotations, "Accessor") != null
                                || annotation(methodAnnotations, "Invoker") != null,
                            mixin.name + "." + method.name + ": ordinary methods make Mixin treat this as "
                                + "an interface mixin, which cannot target the class " + target.name);
                    }
                }
            }
        }
        }
    }

    @Test void everyDeclaredNativeHookResolvesAgainstMinecraft263() throws Exception {
        for (String configName : mixinConfigs()) {
        try (var resource = getClass().getResourceAsStream("/" + configName)) {
            assertNotNull(resource);
            var config = JsonParser.parseReader(new InputStreamReader(resource)).getAsJsonObject();
            String basePackage = config.get("package").getAsString().replace('.', '/');
            for (var name : config.getAsJsonArray("client")) {
                var mixin = read(basePackage + "/" + name.getAsString());
                var mixinAnnotation = annotation(annotations(mixin), "Mixin");
                assertNotNull(mixinAnnotation, mixin.name);
                var targets = (List<?>) value(mixinAnnotation, "value");
                assertEquals(1, targets.size());
                var target = read(((Type) targets.getFirst()).getInternalName());

                for (var field : mixin.fields) {
                    var fieldAnnotations = new ArrayList<AnnotationNode>();
                    if (field.visibleAnnotations != null) fieldAnnotations.addAll(field.visibleAnnotations);
                    if (field.invisibleAnnotations != null) fieldAnnotations.addAll(field.invisibleAnnotations);
                    if (annotation(fieldAnnotations, "Shadow") != null) {
                        assertTrue(target.fields.stream().anyMatch(f -> f.name.equals(field.name) && f.desc.equals(field.desc)),
                            mixin.name + ": shadow field does not match " + target.name + "." + field.name);
                    }
                }

                for (var handler : mixin.methods) {
                    if (annotation(annotations(handler), "Shadow") != null) {
                        assertTrue(target.methods.stream().anyMatch(m -> m.name.equals(handler.name) && m.desc.equals(handler.desc)),
                            mixin.name + ": shadow method does not match " + target.name + "." + handler.name);
                    }
                    for (var declaration : annotations(handler)) {
                        if (declaration.desc.endsWith("/Accessor;")) {
                            String fieldName = (String) value(declaration, "value");
                            assertTrue(target.fields.stream().anyMatch(f -> f.name.equals(fieldName)),
                                target.name + "." + fieldName);
                        }
                        Object selectors = value(declaration, "method");
                        if (!(selectors instanceof List<?> methods)) continue;
                        for (var selector : methods) {
                            String signature = selector.toString();
                            int descriptorStart = signature.indexOf('(');
                            String methodName = descriptorStart < 0 ? signature : signature.substring(0, descriptorStart);
                            String descriptor = descriptorStart < 0 ? null : signature.substring(descriptorStart);
                            var candidates = target.methods.stream().filter(m -> m.name.equals(methodName)
                                && (descriptor == null || m.desc.equals(descriptor))).toList();
                            assertFalse(candidates.isEmpty(), target.name + "." + methodName);
                            if (declaration.desc.endsWith("/Inject;") && value(declaration, "locals") == null) {
                                var handlerArgs = Type.getArgumentTypes(handler.desc);
                                int callback = -1;
                                for (int i = 0; i < handlerArgs.length; i++) {
                                    if (handlerArgs[i].getSort() == Type.OBJECT &&
                                        handlerArgs[i].getInternalName().startsWith("org/spongepowered/asm/mixin/injection/callback/CallbackInfo")) {
                                        callback = i;
                                        break;
                                    }
                                }
                                assertTrue(callback >= 0, mixin.name + "." + handler.name + " lacks a callback");
                                final int capturedArguments = callback;
                                assertTrue(candidates.stream().anyMatch(candidate -> {
                                    var expected = Type.getArgumentTypes(candidate.desc);
                                    if (capturedArguments == 0) return true;
                                    if (capturedArguments != expected.length) return false;
                                    for (int i = 0; i < capturedArguments; i++) {
                                        if (!handlerArgs[i].equals(expected[i])) return false;
                                    }
                                    return true;
                                }), mixin.name + "." + handler.name + " callback arguments differ from " + target.name + "." + methodName);
                            }
                            if (declaration.desc.endsWith("/ModifyConstant;")) {
                                Object constantValue = value(declaration, "constant");
                                var constants = constantValue instanceof List<?> list ? list : List.of(constantValue);
                                for (var entry : constants) {
                                    if (!(entry instanceof AnnotationNode constant)) continue;
                                    Integer wanted = (Integer) value(constant, "intValue");
                                    if (wanted == null) continue;
                                    long matches = candidates.stream().flatMap(candidate -> {
                                        List<Integer> found = new ArrayList<>();
                                        for (var insn : candidate.instructions) {
                                            if (insn instanceof org.objectweb.asm.tree.IntInsnNode integer) found.add(integer.operand);
                                            else if (insn instanceof org.objectweb.asm.tree.LdcInsnNode literal && literal.cst instanceof Integer integer)
                                                found.add(integer);
                                            else if (insn.getOpcode() >= Opcodes.ICONST_M1 && insn.getOpcode() <= Opcodes.ICONST_5)
                                                found.add(insn.getOpcode() - Opcodes.ICONST_0);
                                        }
                                        return found.stream();
                                    }).filter(wanted::equals).count();
                                    assertTrue(matches > 0, target.name + "." + methodName + " constant " + wanted);
                                    Integer allow = (Integer) value(declaration, "allow");
                                    if (allow != null && allow > 0) assertTrue(matches <= allow,
                                        target.name + "." + methodName + " constant " + wanted + " appears " + matches + " times");
                                }
                            }
                            Object atValue = value(declaration, "at");
                            // ModifyConstant identifies its target through `constant`, not `at`.
                            if (atValue == null) continue;
                            var atList = atValue instanceof List<?> list ? list : List.of(atValue);
                            for (var entry : atList) {
                                if (!(entry instanceof AnnotationNode at)) continue;
                                String invokedTarget = (String) value(at, "target");
                                if (invokedTarget == null || invokedTarget.isEmpty()) continue;
                                if ("FIELD".equals(value(at, "value"))) {
                                    Integer opcode = (Integer) value(at, "opcode");
                                    long fieldMatches = candidates.stream().flatMap(m -> {
                                        List<FieldInsnNode> fields = new ArrayList<>();
                                        for (var instruction : m.instructions) {
                                            if (instruction instanceof FieldInsnNode field) fields.add(field);
                                        }
                                        return fields.stream();
                                    }).filter(field -> invokedTarget.equals("L" + field.owner + ";" + field.name + ":" + field.desc)
                                        && (opcode == null || opcode == field.getOpcode())).count();
                                    Integer ordinal = (Integer) value(at, "ordinal");
                                    assertTrue(fieldMatches > (ordinal == null ? -1 : ordinal) && fieldMatches > 0,
                                        target.name + "." + methodName + " -> " + invokedTarget + ", ordinal " + ordinal);
                                    continue;
                                }
                                var calls = candidates.stream().flatMap(m -> {
                                    List<MethodInsnNode> instructions = new ArrayList<>();
                                    for (var instruction : m.instructions) {
                                        if (instruction instanceof MethodInsnNode call) instructions.add(call);
                                    }
                                    return instructions.stream();
                                }).filter(call -> invokedTarget.equals("L" + call.owner + ";" + call.name + call.desc)).toList();
                                String location = mixin.name + "." + handler.name + " -> " + target.name + "."
                                    + methodName + " -> " + invokedTarget;
                                Integer ordinal = (Integer) value(at, "ordinal");
                                assertTrue(calls.size() > (ordinal == null ? -1 : ordinal) && !calls.isEmpty(), location);
                                int selected = ordinal != null && ordinal >= 0 ? 1 : calls.size();
                                Integer required = (Integer) value(declaration, "require");
                                Integer allowed = (Integer) value(declaration, "allow");
                                if (required != null && required > 0) assertTrue(selected >= required, location);
                                if (allowed != null && allowed > 0) assertTrue(selected <= allowed, location);
                                for (var call : calls) validateInvocationHandler(declaration, handler, call, location);
                            }
                        }
                    }
                }
            }
        }
        }
    }

    /** A selector resolving is insufficient if the injector's argument/return descriptor is wrong. */
    private static void validateInvocationHandler(AnnotationNode declaration, MethodNode handler,
                                                 MethodInsnNode call, String location) {
        var supplied = new ArrayList<Type>();
        var arguments = Type.getArgumentTypes(handler.desc);
        for (int i = 0; i < arguments.length; i++) {
            if (!isCapturedLocal(handler, i)) supplied.add(arguments[i]);
        }
        var calledArguments = List.of(Type.getArgumentTypes(call.desc));
        var calledResult = Type.getReturnType(call.desc);
        var handlerResult = Type.getReturnType(handler.desc);
        if (declaration.desc.endsWith("/Redirect;")) {
            var expected = new ArrayList<Type>();
            if (call.getOpcode() != Opcodes.INVOKESTATIC) expected.add(Type.getObjectType(call.owner));
            expected.addAll(calledArguments);
            assertEquals(expected, supplied, location + " redirect arguments");
            assertEquals(calledResult, handlerResult, location + " redirect result");
        } else if (declaration.desc.endsWith("/ModifyArg;")) {
            Integer index = (Integer) value(declaration, "index");
            assertNotNull(index, location + " should identify its modified argument explicitly");
            assertTrue(index >= 0 && index < calledArguments.size(), location + " argument index");
            assertEquals(calledArguments.get(index), handlerResult, location + " modified result");
            assertTrue(supplied.equals(List.of(handlerResult)) || supplied.equals(calledArguments),
                location + " argument modifier must accept its argument or the entire invocation argument list");
        } else if (declaration.desc.endsWith("/ModifyExpressionValue;")) {
            assertFalse(supplied.isEmpty(), location);
            assertEquals(calledResult, supplied.getFirst(), location + " expression input");
            assertEquals(calledResult, handlerResult, location + " expression result");
        }
    }

    private static boolean isCapturedLocal(MethodNode handler, int parameter) {
        if (handler.visibleParameterAnnotations != null && parameter < handler.visibleParameterAnnotations.length) {
            var annotations = handler.visibleParameterAnnotations[parameter];
            if (annotations != null && annotation(annotations, "Local") != null) return true;
        }
        if (handler.invisibleParameterAnnotations != null && parameter < handler.invisibleParameterAnnotations.length) {
            var annotations = handler.invisibleParameterAnnotations[parameter];
            if (annotations != null && annotation(annotations, "Local") != null) return true;
        }
        return false;
    }

    private List<String> mixinConfigs() throws Exception {
        try (var resource = getClass().getResourceAsStream("/fabric.mod.json")) {
            assertNotNull(resource);
            var metadata = JsonParser.parseReader(new InputStreamReader(resource)).getAsJsonObject();
            var result = new ArrayList<String>();
            for (var entry : metadata.getAsJsonArray("mixins")) {
                result.add(entry.isJsonPrimitive() ? entry.getAsString() : entry.getAsJsonObject().get("config").getAsString());
            }
            assertFalse(result.isEmpty());
            return result;
        }
    }

    private ClassNode read(String name) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/" + name + ".class")) {
            assertNotNull(stream, "Missing class " + name);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        }
    }

    private static List<AnnotationNode> annotations(ClassNode node) {
        var result = new ArrayList<AnnotationNode>();
        if (node.visibleAnnotations != null) result.addAll(node.visibleAnnotations);
        if (node.invisibleAnnotations != null) result.addAll(node.invisibleAnnotations);
        return result;
    }

    private static List<AnnotationNode> annotations(MethodNode node) {
        var result = new ArrayList<AnnotationNode>();
        if (node.visibleAnnotations != null) result.addAll(node.visibleAnnotations);
        if (node.invisibleAnnotations != null) result.addAll(node.invisibleAnnotations);
        return result;
    }

    private static AnnotationNode annotation(List<AnnotationNode> annotations, String simpleName) {
        return annotations.stream().filter(a -> a.desc.endsWith("/" + simpleName + ";")).findFirst().orElse(null);
    }

    private static Object value(AnnotationNode annotation, String key) {
        if (annotation.values != null) {
            for (int i = 0; i < annotation.values.size(); i += 2) {
                if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
            }
        }
        return null;
    }
}

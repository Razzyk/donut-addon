package dev.donut.addon;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Check the shipped Mixin declarations against the actual dependency without initializing Minecraft. */
class NativeHookTargetsTest {
    @Test void accessorInterfacesTargetingClassesHaveNoHelperMethods() throws Exception {
        try (var resource = getClass().getResourceAsStream("/donut-addon.mixins.json")) {
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

    @Test void everyDeclaredNativeHookResolvesAgainstMinecraft263() throws Exception {
        for (String configName : List.of("donut-addon.mixins.json", "donut-mv.mixins.json",
                "donut-mv-events.mixins.json")) {
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
                            Object atValue = value(declaration, "at");
                            // ModifyConstant identifies its target through `constant`, not `at`.
                            if (atValue == null) continue;
                            var atList = atValue instanceof List<?> list ? list : List.of(atValue);
                            for (var entry : atList) {
                                if (!(entry instanceof AnnotationNode at)) continue;
                                String invokedTarget = (String) value(at, "target");
                                if (invokedTarget == null || invokedTarget.isEmpty()) continue;
                                long matches = candidates.stream().flatMap(m -> {
                                    List<MethodInsnNode> instructions = new ArrayList<>();
                                    for (var instruction : m.instructions) {
                                        if (instruction instanceof MethodInsnNode call) instructions.add(call);
                                    }
                                    return instructions.stream();
                                }).filter(call -> invokedTarget.equals("L" + call.owner + ";" + call.name + call.desc)).count();
                                assertEquals(1, matches, target.name + "." + methodName + " -> " + invokedTarget);
                            }
                        }
                    }
                }
            }
        }
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

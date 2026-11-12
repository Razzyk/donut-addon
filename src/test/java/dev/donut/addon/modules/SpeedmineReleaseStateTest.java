package dev.donut.addon.modules;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.InputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class SpeedmineReleaseStateTest {
    @Test
    void heldBreakReleasesThenUsesOneFreshVanillaStartForEachHardTarget() {
        var state = new SpeedmineReleaseState();
        for (int block = 0; block < 2; block++) {
            assertTrue(state.onBlockCompleted(true, true));
            assertFalse(state.filterAttack(true, true), "one continuation becomes the release");
            assertTrue(state.shouldRestart(true, true), "restart waits for a held valid target");
            assertFalse(state.filterAttack(true, true), "no old continuation can damage the next target");
            state.restartStarted(); // Tail hook succeeded through vanilla startDestroyBlock.
            assertTrue(state.filterAttack(true, true), "normal hardness-based progress resumes after START");
        }
    }

    @Test
    void existingPhysicalReleaseSatisfiesPendingCycle() {
        var state = new SpeedmineReleaseState();
        assertTrue(state.onBlockCompleted(true, true));
        assertFalse(state.filterAttack(true, false));
        assertTrue(state.filterAttack(true, true), "the next user press is not swallowed");
    }

    @Test
    void inactiveOrReleasedCompletionDoesNotClearCooldownOrScheduleARelease() {
        var state = new SpeedmineReleaseState();
        assertFalse(state.onBlockCompleted(false, true));
        assertTrue(state.filterAttack(false, true));
        assertFalse(state.onBlockCompleted(true, false));
        assertTrue(state.filterAttack(true, true));
    }

    @Test
    void disablingDuringPendingReleaseRestoresTheRealHeldInput() {
        var state = new SpeedmineReleaseState();
        assertTrue(state.onBlockCompleted(true, true));
        assertTrue(state.filterAttack(false, true));
        assertTrue(state.filterAttack(true, true), "reenabling starts with a clean cycle");
    }

    @Test
    void completionHookCannotMineOrSendItsOwnBlockActionPackets() throws Exception {
        var completion = read("/dev/donut/addon/mixin/MixinDonutSpeedmine.class");
        var callback = completion.methods.stream()
            .filter(method -> method.name.equals("donut$releaseAfterBreak"))
            .findFirst().orElseThrow();
        for (var instruction : callback.instructions) {
            if (instruction instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTFIELD) {
                assertFalse(field.name.equals("destroyProgress"), "must never preserve or raise target damage");
                assertFalse(field.name.equals("destroyTicks"), "must not alter mining time");
            }
            if (instruction instanceof MethodInsnNode call) {
                assertFalse(call.name.equals("destroyBlock") || call.name.equals("stopDestroyBlock"),
                    "completion already sent vanilla STOP; no synthetic break/abort");
                assertFalse(call.owner.equals("net/minecraft/network/protocol/game/ServerboundPlayerActionPacket"),
                    "the feature must not synthesize mining packets");
            }
        }
    }

    @Test
    void restartHookUsesTheNativeStartMethodRatherThanAnInstantBreakCall() throws Exception {
        var input = read("/dev/donut/addon/mixin/MixinDonutSpeedmineInput.class");
        var restart = input.methods.stream()
            .filter(method -> method.name.equals("donut$restartFreshTargetAfterRelease"))
            .findFirst().orElseThrow();
        long starts = Arrays.stream(restart.instructions.toArray())
            .filter(MethodInsnNode.class::isInstance).map(MethodInsnNode.class::cast)
            .filter(call -> call.name.equals("startDestroyBlock")
                && call.owner.equals("net/minecraft/client/multiplayer/MultiPlayerGameMode"))
            .count();
        assertEquals(1, starts, "restarts through the normal START path once");
        assertFalse(Arrays.stream(restart.instructions.toArray()).filter(MethodInsnNode.class::isInstance)
            .map(MethodInsnNode.class::cast).anyMatch(call -> call.name.equals("destroyBlock")));
    }

    private ClassNode read(String resource) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            var node = new ClassNode();
            new ClassReader(stream).accept(node, 0);
            return node;
        }
    }
}

package dev.donut.addon.mv.compat.utils.misc;

import com.mojang.blaze3d.platform.InputConstants;
import dev.donut.addon.mv.compat.utils.misc.input.Input;
import java.util.Objects;

/** Small key binding value used by the ported movement settings. */
public final class Keybind {
    private final InputConstants.Key key;

    private Keybind(InputConstants.Key key) {
        this.key = key;
    }

    /** Converts the legacy GLFW key code used by the source addon to a native MC key. */
    public static Keybind fromKey(int legacyKeyCode) {
        if (legacyKeyCode < 0) return new Keybind(InputConstants.UNKNOWN);

        // The source addon only defines V as a default key. Its GLFW code (86) is not
        // an SDL scancode, so resolve it by the stable Minecraft key name.
        if (legacyKeyCode >= 'A' && legacyKeyCode <= 'Z') {
            return new Keybind(InputConstants.getKey("key.keyboard." + Character.toLowerCase((char) legacyKeyCode)));
        }
        return new Keybind(InputConstants.UNKNOWN);
    }

    /** Wraps a key value already represented in Minecraft's native SDL key space. */
    public static Keybind fromNative(InputConstants.Key key) {
        return new Keybind(key == null ? InputConstants.UNKNOWN : key);
    }

    public int keyCode() {
        return key.getValue();
    }

    public boolean isPressed() {
        return key.getType() == InputConstants.Type.MOUSE
            ? Input.isNativeButtonPressed(key.getValue())
            : Input.isNativeKeyPressed(key.getValue());
    }

    public boolean isSet() {
        return !key.equals(InputConstants.UNKNOWN);
    }

    @Override
    public String toString() {
        return key.getDisplayName().getString();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Keybind bind && key.equals(bind.key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(key);
    }
}

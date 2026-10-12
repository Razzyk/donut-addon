package dev.donut.addon.mv.compat.utils.misc.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.sdl.SDLMouse;

/** Native key state helpers used by the MV movement modules. */
public final class Input {
    private Input() {
    }

    public static boolean isPressed(KeyMapping mapping) {
        return mapping != null && mapping.isDown();
    }

    /** Tests a keyboard SDL scancode, as returned by InputConstants.Key#getValue(). */
    public static boolean isNativeKeyPressed(int scanCode) {
        return scanCode >= 0 && InputConstants.isKeyDown(scanCode);
    }

    /** Tests a mouse button value from InputConstants.Type.MOUSE. */
    public static boolean isNativeButtonPressed(int button) {
        int mask = switch (button) {
            case 0 -> SDLMouse.SDL_BUTTON_LMASK;
            case 1 -> SDLMouse.SDL_BUTTON_RMASK;
            case 2 -> SDLMouse.SDL_BUTTON_MMASK;
            case 3 -> SDLMouse.SDL_BUTTON_X1MASK;
            case 4 -> SDLMouse.SDL_BUTTON_X2MASK;
            default -> 0;
        };
        return mask != 0 && (SDLMouse.SDL_GetMouseState(null, null) & mask) != 0;
    }
}

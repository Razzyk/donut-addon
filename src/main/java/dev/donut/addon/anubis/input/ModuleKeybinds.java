package dev.donut.addon.anubis.input;
public final class ModuleKeybinds {
    private ModuleKeybinds() {}
    // SDL function/navigation key codes use high bits; they are valid keyboard bindings.
    public static boolean isBindable(int key) { return key >= 0; }
}

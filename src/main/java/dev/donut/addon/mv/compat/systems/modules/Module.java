package dev.donut.addon.mv.compat.systems.modules;

import dev.donut.addon.mv.MVModuleBase;
import dev.donut.addon.mv.compat.MeteorClient;
import dev.donut.addon.mv.compat.settings.Settings;
import dev.donut.addon.mv.compat.settings.Setting;
import dev.donut.addon.mv.compat.utils.player.ChatUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Native ClientModule subclass, keeping the source's lifecycle and settings entry points. */
public class Module extends MVModuleBase {
    protected final Minecraft mc = Minecraft.getInstance();
    public final Settings settings = new Settings(this);
    public final String name;
    public final String title;
    public final String description;
    public boolean runInMainMenu;
    private boolean activating;
    private boolean activationCancelled;
    public Module(Category category, String name, String description) {
        super(name, category.nativeCategory);
        this.name = name; this.title = name; this.description = description;
        literalDescription(() -> description);
        MeteorClient.EVENT_BUS.subscribe(this);
    }
    @Override public void legacyActivate() {
        activating = true; activationCancelled = false;
        try { for (Setting<?> setting : settings.getAll()) setting.moduleActivated(); onActivate(); }
        finally {
            activating = false;
            if (activationCancelled) mc.execute(() -> setEnabled(false));
        }
    }
    @Override public void legacyDeactivate() { onDeactivate(); }
    public void onActivate() {}
    public void onDeactivate() {}
    public boolean isActive() { return !activationCancelled && (getEnabled() || activating); }
    public void enable() { activationCancelled = false; setEnabled(true); }
    public void disable() {
        if (activating) activationCancelled = true;
        else setEnabled(false);
    }
    public void toggle() { if (isActive()) disable(); else enable(); }
    public void info(String format, Object... args) { message("", format, args); }
    public void warning(String format, Object... args) { message("Warning: ", format, args); }
    public void error(String format, Object... args) { message("Error: ", format, args); }
    private void message(String severity, String format, Object[] args) {
        ChatUtils.sendMsg(Component.literal("[" + title + "] " + severity + String.format(format, args)));
    }
}

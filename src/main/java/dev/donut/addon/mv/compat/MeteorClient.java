package dev.donut.addon.mv.compat;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
/** Source facade only: no Meteor classes or runtime are loaded. */
public final class MeteorClient {
    public static final Minecraft mc = Minecraft.getInstance();
    public static final EventBus EVENT_BUS = new EventBus();
    public static final Logger LOG = LoggerFactory.getLogger("Donut/MV");
    private MeteorClient() {}
}

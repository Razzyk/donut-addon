package dev.donut.addon.expansion.schematic;

import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import java.util.UUID;

final class PlacementFixturePlayer extends Player {
    PlacementFixturePlayer(PlacementFixtureLevel level) {
        super(level, new GameProfile(new UUID(0L, 1L), "DonutFixture"));
        setPos(0.5, 1.0, -2.5);
    }
    @Override public GameType gameMode() { return GameType.SURVIVAL; }
}

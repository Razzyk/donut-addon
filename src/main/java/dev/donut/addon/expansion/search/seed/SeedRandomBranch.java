package dev.donut.addon.expansion.search.seed;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.Objects;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/** Cloneable worldgen decisions using Minecraft's own public random implementation and codec. */
public final class SeedRandomBranch {
    public record State(String encoded) {
        public State {
            Objects.requireNonNull(encoded);
            if (encoded.length() > 512) throw new IllegalArgumentException("Oversized random state");
        }
    }
    private final XoroshiroRandomSource source;
    private final WorldgenRandom random;
    private State saved;

    private SeedRandomBranch(XoroshiroRandomSource source) {
        this.source = source;
        this.random = new WorldgenRandom(source);
    }

    public static SeedRandomBranch feature(long seed, int blockX, int blockZ, int index, int step) {
        var branch = new SeedRandomBranch(new XoroshiroRandomSource(seed));
        long decoration = branch.random.setDecorationSeed(seed,blockX,blockZ);
        branch.random.setFeatureSeed(decoration,index,step);
        return branch;
    }

    public static SeedRandomBranch resume(State state) {
        var source = XoroshiroRandomSource.CODEC.parse(JsonOps.INSTANCE,JsonParser.parseString(state.encoded())).getOrThrow();
        var branch = new SeedRandomBranch(source);
        branch.saved = state;
        return branch;
    }

    public State freeze() {
        if (saved == null) saved = new State(XoroshiroRandomSource.CODEC.encodeStart(JsonOps.INSTANCE,source).getOrThrow().toString());
        return saved;
    }
    public SeedRandomBranch branch() { return resume(freeze()); }
    /** Native feature execution consumes this same Minecraft stream; freeze() remains its fork point. */
    public RandomSource nativeSource() { saved = null; return random; }
    public int rollInt(int bound) { saved = null; return random.nextInt(bound); }
    public float unitFloat() { saved = null; return random.nextFloat(); }
    public double unitDouble() { saved = null; return random.nextDouble(); }
    public long wordLong() { saved = null; return random.nextLong(); }
    /** Advances the OreFeature's pre-gate angle and endpoint jitter draws. */
    public void skipOreGatePrefix() {
        saved = null;
        random.nextFloat();
        random.nextInt(3);
        random.nextInt(3);
    }
    public void advance(int count) {
        if (count < 0) throw new IllegalArgumentException("Negative random draw count");
        saved = null;
        random.consumeCount(count);
    }
}

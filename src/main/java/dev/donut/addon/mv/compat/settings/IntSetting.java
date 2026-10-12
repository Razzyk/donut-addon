package dev.donut.addon.mv.compat.settings;

public final class IntSetting {
    private IntSetting() {}
    public static final class Builder extends SettingBuilder<Integer, Builder> {
        public Builder() { defaultValue = 0; sliderMin = 0.0; sliderMax = 10.0; }
        @Override protected Builder self() { return this; }
        public Builder range(int min, int max) { bounds(Math.min(min, max), Math.max(min, max)); return this; }
        public Builder min(int min) { this.min = (double)min; return this; }
        public Builder max(int max) { this.max = (double)max; return this; }
        public Builder sliderRange(int min, int max) { sliderBounds(Math.min(min, max), Math.max(min, max)); return this; }
        public Builder sliderMin(int min) { sliderMin = (double)min; return this; }
        public Builder sliderMax(int max) { sliderMax = (double)max; return this; }
        public Setting<Integer> build() { return buildSetting("int"); }
    }
}

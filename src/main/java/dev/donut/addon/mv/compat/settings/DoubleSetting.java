package dev.donut.addon.mv.compat.settings;

public final class DoubleSetting {
    private DoubleSetting() {}
    public static final class Builder extends SettingBuilder<Double, Builder> {
        public Builder() { defaultValue = 0.0; sliderMin = 0.0; sliderMax = 10.0; }
        @Override protected Builder self() { return this; }
        public Builder range(double min, double max) { bounds(Math.min(min, max), Math.max(min, max)); return this; }
        public Builder min(double min) { this.min = min; return this; }
        public Builder max(double max) { this.max = max; return this; }
        public Builder sliderRange(double min, double max) { sliderBounds(min, max); return this; }
        public Builder sliderMin(double min) { sliderMin = min; return this; }
        public Builder sliderMax(double max) { sliderMax = max; return this; }
        public Setting<Double> build() { return buildSetting("double"); }
    }
}

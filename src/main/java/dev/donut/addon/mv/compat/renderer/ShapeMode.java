package dev.donut.addon.mv.compat.renderer;

public enum ShapeMode {
    Lines, Sides, Both;
    public boolean lines() { return this != Sides; }
    public boolean sides() { return this != Lines; }
}

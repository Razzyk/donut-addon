package dev.donut.addon.anubis.module.setting;
public class ModeSetting extends Setting<String>{ public final String[] choices; public ModeSetting(String name,String value,String[] choices){super(name,value);this.choices=choices.clone();} public boolean is(String value){return get().equalsIgnoreCase(value);} }

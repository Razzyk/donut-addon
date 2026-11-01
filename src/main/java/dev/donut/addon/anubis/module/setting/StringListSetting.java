package dev.donut.addon.anubis.module.setting;
import java.util.*;
public class StringListSetting extends Setting<List<String>>{ public boolean itemPicker; public StringListSetting(String name,String[] value){super(name,new ArrayList<>(Arrays.asList(value)));} public StringListSetting itemPicker(){itemPicker=true;return this;} }

package dev.donut.addon.mv.compat.events;

import java.lang.annotation.*;
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface EventHandler { int priority() default 0; }

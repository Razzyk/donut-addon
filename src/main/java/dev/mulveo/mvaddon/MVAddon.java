package dev.mulveo.mvaddon;

import dev.donut.addon.AddonCategories;
import dev.donut.addon.mv.compat.systems.modules.Category;
/** Category identities retained for the adapted feature constructors. */
public final class MVAddon {
    public static final Category BASEFINDING = new Category(AddonCategories.BASEFINDING);
    public static final Category UTILITY = new Category(AddonCategories.UTILITY);
    public static final Category VISUAL = UTILITY;
    public static final Category CATEGORY = BASEFINDING;
    private MVAddon() {}
}

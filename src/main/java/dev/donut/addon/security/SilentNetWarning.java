package dev.donut.addon.security;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

/** Local advisory text; untrusted filenames never supply formatting, commands, or link targets. */
public final class SilentNetWarning {
    public static final URI ARTICLE = URI.create("https://www.malwation.com/blog/inside-silentnet-analysis-of-a-java-based-infostealer-with-blockchain-based-c2");

    private SilentNetWarning() { }

    public static List<Component> messages(List<String> indicators, boolean incomplete) {
        if (indicators.isEmpty()) return List.of();
        String evidence = indicators.stream().limit(2).map(SilentNetWarning::safeIdentifier)
            .collect(Collectors.joining("; "));
        if (indicators.size() > 2) evidence += " (+" + (indicators.size() - 2) + " more)";
        if (incomplete) evidence += " [check incomplete]";
        return List.of(
            line("Possible SilentNET indicators: " + evidence, ChatFormatting.RED),
            line("This may be a false positive. SilentNET can steal passwords, login sessions and wallet data.", ChatFormatting.GOLD),
            line("Close Minecraft, disconnect this PC, and get a trusted malware scan/help.", ChatFormatting.YELLOW),
            line("From a clean device, change passwords and revoke sessions. If confirmed, infection may need a clean OS reinstall/factory reset; deleting a mod alone may not remove it.", ChatFormatting.YELLOW),
            Component.literal("[Donut] Read: ").withStyle(ChatFormatting.GOLD).append(
                Component.literal("Malwation SilentNET analysis").withStyle(style -> style
                    .withColor(ChatFormatting.AQUA).withUnderlined(true)
                    .withClickEvent(new ClickEvent.OpenUrl(ARTICLE))))
        );
    }

    private static Component line(String text, ChatFormatting color) {
        return Component.literal("[Donut] " + text).withStyle(color);
    }

    private static String safeIdentifier(String text) {
        if (text == null) return "unknown indicator";
        var safe = new StringBuilder();
        text.codePoints().limit(80).forEach(point -> {
            if (Character.isISOControl(point) || Character.getType(point) == Character.FORMAT || point == '\u00a7')
                safe.append('?');
            else safe.appendCodePoint(point);
        });
        return safe.toString();
    }
}

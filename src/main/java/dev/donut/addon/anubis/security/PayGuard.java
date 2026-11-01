package dev.donut.addon.anubis.security;

import java.util.regex.Pattern;

/** Validate payment commands at the point where the macro submits them. */
public final class PayGuard {
    private static final Pattern PAYMENT = Pattern.compile("pay \\.?[A-Za-z0-9_]{1,16} [0-9]+(?:\\.[0-9]+)?[kKmMbBtT]?");
    private PayGuard() {}
    public static void allow(String command) {
        if (command == null || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Invalid macro command");
        if (command.startsWith("pay ") && !PAYMENT.matcher(command).matches())
            throw new IllegalArgumentException("Invalid payment recipient or amount");
    }
}

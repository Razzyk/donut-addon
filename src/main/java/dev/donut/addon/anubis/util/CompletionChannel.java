package dev.donut.addon.anubis.util;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundCommandSuggestionsPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;

import java.util.*;

/** Tracks macro completions independently of the chat screen's completion request ids. */
public final class CompletionChannel {
    public static final CompletionChannel INSTANCE = new CompletionChannel();
    public enum Form { SLASH, BARE, BOTH }
    private static final long TIMEOUT_MS = 15_000;
    private final Map<Integer, Request> pending = new HashMap<>();
    private int nextId = 1_000_000_000;

    public synchronized int fire(Object owner, String partial, Form form) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) throw new IllegalStateException("Not connected");
        prune(System.currentTimeMillis());
        if (pending.size() >= 1024) throw new IllegalStateException("Too many pending completions");
        String bare = partial.startsWith("/") ? partial.substring(1) : partial;
        int id = nextId++;
        Request request = new Request(owner, System.currentTimeMillis(), form == Form.BOTH ? 2 : 1);
        pending.put(id, request);
        connection.send(new ServerboundCommandSuggestionPacket(id, form == Form.BARE ? bare : "/" + bare));
        if (form == Form.BOTH) {
            int secondId = nextId++;
            pending.put(secondId, request);
            connection.send(new ServerboundCommandSuggestionPacket(secondId, bare));
        }
        return id;
    }

    public synchronized void receive(Packet<?> packet) {
        if (!(packet instanceof ClientboundCommandSuggestionsPacket suggestions)) return;
        Request request = pending.get(suggestions.id());
        if (request == null || !request.received.add(suggestions.id())) return;
        suggestions.toSuggestions().getList().forEach(value -> request.names.add(value.getText()));
    }
    public synchronized List<String> poll(int id) {
        Request request = pending.get(id);
        return request == null || request.received.size() < request.responses ? null : List.copyOf(request.names);
    }
    public synchronized void forget(int id) {
        Request request = pending.get(id);
        if (request != null) pending.values().removeIf(value -> value == request);
    }
    public synchronized void prune(long now) { pending.values().removeIf(value -> now - value.created > TIMEOUT_MS); }
    public synchronized void clear(Object owner) { pending.values().removeIf(value -> value.owner == owner); }
    public synchronized void reset() { pending.clear(); }

    private static final class Request {
        final Object owner;
        final long created;
        final int responses;
        final Set<Integer> received = new HashSet<>();
        final Set<String> names = new LinkedHashSet<>();
        Request(Object owner, long created, int responses) { this.owner = owner; this.created = created; this.responses = responses; }
    }
}

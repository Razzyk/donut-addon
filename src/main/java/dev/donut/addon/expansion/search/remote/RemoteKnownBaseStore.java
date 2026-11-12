package dev.donut.addon.expansion.search.remote;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Per-server and per-dimension persistence for chunks visited without a known light signal. */
public final class RemoteKnownBaseStore implements AutoCloseable {
    private static volatile RemoteKnownBaseStore sharedInstance;
    private static final Logger LOGGER = LoggerFactory.getLogger("DonutAddon");
    private static final int FORMAT_VERSION = 1;
    private static final int RADIUS = 2;
    private static final int MAX_CHUNKS = 250_000;
    private static final long MAX_FILE_BYTES = 32L * 1024L * 1024L;
    private static final long WRITE_DELAY_NANOS = 10_000_000_000L;
    private static final long WAIT_SECONDS = 10L;

    private final Path directory;
    private final Map<String, Bucket> buckets = new HashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Donut known-base saves");
        thread.setDaemon(true);
        return thread;
    });
    private CompletableFuture<Void> lastWrite = CompletableFuture.completedFuture(null);
    private volatile boolean writeFailed;
    private Bucket active;
    private boolean dirty;
    private long dirtySince;
    private boolean closed;

    public RemoteKnownBaseStore(Path directory) {
        this.directory = directory.toAbsolutePath().normalize();
    }

    public static RemoteKnownBaseStore get() {
        RemoteKnownBaseStore store = sharedInstance;
        if (store != null) return store;
        synchronized (RemoteKnownBaseStore.class) {
            store = sharedInstance;
            if (store == null) {
                store = new RemoteKnownBaseStore(FabricLoader.getInstance().getConfigDir()
                    .resolve("donut-addon").resolve("known-bases"));
                sharedInstance = store;
            }
            return store;
        }
    }

    /** Capture can initialize persistence even when its display module has never been enabled. */
    public static void shutdownShared() {
        RemoteKnownBaseStore store = sharedInstance;
        if (store != null) store.close();
    }

    /** Disconnecting before any world was entered must not initialize storage or its loader path. */
    public static void endSharedSession() {
        RemoteKnownBaseStore store = sharedInstance;
        if (store != null) store.select(null, null);
    }

    /** Selects the exact server/save and dimension bucket for the current client world. */
    public synchronized void selectCurrent(Minecraft client, ClientLevel level) {
        if (client == null || level == null) {
            this.select(null, null);
            return;
        }
        IntegratedServer integrated = client.getSingleplayerServer();
        String server;
        if (integrated != null) {
            Path folder = integrated.getWorldPath(LevelResource.ROOT).normalize().getFileName();
            server = singleplayerKey(folder == null ? "world" : folder.toString());
        } else {
            ServerData data = client.getCurrentServer();
            server = serverKey(data == null ? null : data.ip);
        }
        this.select(server, level.dimension().identifier().toString());
    }

    public synchronized void select(String server, String dimension) {
        if (this.closed) return;
        if (server == null || dimension == null) {
            if (this.active != null) this.flush();
            this.active = null;
            return;
        }
        String fileName = fileName(server, dimension);
        Bucket bucket = this.buckets.get(fileName);
        if (bucket == null) {
            bucket = new Bucket(this.directory.resolve(fileName), server, dimension);
            load(bucket);
            this.buckets.put(fileName, bucket);
        }
        if (bucket != this.active) {
            if (this.active != null) this.flush();
            this.active = bucket;
        }
    }

    public synchronized boolean isProtected(long chunkKey) {
        return this.active != null && this.active.chunks.contains(chunkKey);
    }

    /** Existing flags survive; only new signals in a protected chunk are suppressed. */
    public synchronized boolean flagAllowed(long chunkKey, boolean alreadyFlagged) {
        return alreadyFlagged || !this.isProtected(chunkKey);
    }

    public synchronized boolean protect(long chunkKey) {
        if (this.closed || this.active == null || !this.active.chunks.add(chunkKey)) return false;
        if (this.active.chunks.size() > MAX_CHUNKS) {
            var oldest = this.active.chunks.iterator();
            oldest.next();
            oldest.remove();
        }
        this.markChanged(this.active);
        return true;
    }

    public synchronized int size() { return this.active == null ? 0 : this.active.chunks.size(); }

    public static long chunkKey(int x, int z) { return ChunkPos.pack(x, z); }

    public static void forEachInArea(int centerX, int centerZ, java.util.function.LongConsumer action) {
        for (int x = centerX - RADIUS; x <= centerX + RADIUS; x++) {
            for (int z = centerZ - RADIUS; z <= centerZ + RADIUS; z++) action.accept(chunkKey(x, z));
        }
    }

    public static String serverKey(String address) {
        String normalized = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith(":25565")) normalized = normalized.substring(0, normalized.length() - ":25565".length());
        return normalized.isEmpty() ? "unknown" : normalized;
    }

    public static String singleplayerKey(String worldFolder) {
        return "singleplayer/" + (worldFolder == null || worldFolder.isBlank() ? "world" : worldFolder);
    }

    public synchronized void tickAutosave() {
        this.retryFailedWrites();
        if (this.dirty && System.nanoTime() - this.dirtySince >= WRITE_DELAY_NANOS) this.saveChanged();
    }

    public synchronized void flush() {
        this.retryFailedWrites();
        if (this.dirty) this.saveChanged();
    }

    public void flushAndWait() {
        this.flush();
        try {
            this.lastWrite.get(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException failed) {
            LOGGER.warn("Known remote-base chunks may not all be saved", failed);
        }
    }

    @Override public void close() {
        synchronized (this) {
            if (this.closed) return;
        }
        this.flushAndWait();
        synchronized (this) { this.closed = true; }
        this.writer.shutdown();
        try {
            if (!this.writer.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) this.writer.shutdownNow();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            this.writer.shutdownNow();
        }
    }

    private void markChanged(Bucket bucket) {
        if (bucket.readOnly) return;
        bucket.dirty = true;
        if (!this.dirty) {
            this.dirty = true;
            this.dirtySince = System.nanoTime();
        }
    }

    private void retryFailedWrites() {
        if (!this.writeFailed) return;
        this.writeFailed = false;
        for (Bucket bucket : this.buckets.values()) {
            if (bucket.writeFailed) {
                bucket.writeFailed = false;
                this.markChanged(bucket);
            }
        }
    }

    private void saveChanged() {
        this.dirty = false;
        for (Bucket bucket : this.buckets.values()) {
            if (!bucket.dirty) continue;
            bucket.dirty = false;
            long[] chunks = bucket.chunks.stream().mapToLong(Long::longValue).toArray();
            this.lastWrite = CompletableFuture.runAsync(() -> {
                try {
                    write(bucket, chunks);
                } catch (IOException | RuntimeException failed) {
                    LOGGER.warn("Failed to save known remote-base chunks to {}", bucket.file, failed);
                    bucket.writeFailed = true;
                    this.writeFailed = true;
                }
            }, this.writer);
        }
    }

    private static void write(Bucket bucket, long[] chunks) throws IOException {
        Files.createDirectories(bucket.file.getParent());
        Path temporary = bucket.file.resolveSibling(bucket.file.getFileName() + ".tmp");
        try (JsonWriter json = new JsonWriter(Files.newBufferedWriter(temporary, StandardCharsets.UTF_8))) {
            json.setIndent("  ");
            json.beginObject();
            json.name("version").value(FORMAT_VERSION);
            json.name("server").value(bucket.server);
            json.name("dimension").value(bucket.dimension);
            json.name("count").value(chunks.length);
            json.name("chunks").beginArray();
            for (long chunk : chunks) {
                json.beginArray().value(ChunkPos.getX(chunk)).value(ChunkPos.getZ(chunk)).endArray();
            }
            json.endArray();
            json.endObject();
        }
        try {
            Files.move(temporary, bucket.file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException unsupportedAtomicMove) {
            Files.move(temporary, bucket.file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void load(Bucket bucket) {
        if (!Files.isRegularFile(bucket.file)) return;
        try {
            long size = Files.size(bucket.file);
            if (size > MAX_FILE_BYTES) throw new IOException("known-base file exceeds the safety limit");
            int version = 0;
            String server = null, dimension = null;
            LinkedHashSet<Long> loaded = new LinkedHashSet<>();
            try (JsonReader json = new JsonReader(Files.newBufferedReader(bucket.file, StandardCharsets.UTF_8))) {
                json.beginObject();
                while (json.hasNext()) {
                    switch (json.nextName()) {
                        case "version" -> version = json.nextInt();
                        case "server" -> server = json.nextString();
                        case "dimension" -> dimension = json.nextString();
                        case "chunks" -> readChunks(json, loaded);
                        default -> json.skipValue();
                    }
                }
                json.endObject();
            }
            if (version != FORMAT_VERSION || !bucket.server.equals(server) || !bucket.dimension.equals(dimension)) {
                bucket.readOnly = true;
                LOGGER.warn("Skipping incompatible or mismatched known-base file {}", bucket.file);
                return;
            }
            bucket.chunks.addAll(loaded);
        } catch (IOException | RuntimeException failed) {
            bucket.readOnly = true;
            LOGGER.warn("Failed to load known remote-base chunks from {}; preserving the file", bucket.file, failed);
        }
    }

    private static void readChunks(JsonReader json, Set<Long> target) throws IOException {
        if (json.peek() != JsonToken.BEGIN_ARRAY) { json.skipValue(); return; }
        json.beginArray();
        while (json.hasNext() && target.size() < MAX_CHUNKS) {
            if (json.peek() != JsonToken.BEGIN_ARRAY) { json.skipValue(); continue; }
            json.beginArray();
            long x = wholeInt(json), z = wholeInt(json);
            while (json.hasNext()) json.skipValue();
            json.endArray();
            if (x != Long.MIN_VALUE && z != Long.MIN_VALUE) target.add(chunkKey((int)x, (int)z));
        }
        while (json.hasNext()) json.skipValue();
        json.endArray();
    }

    private static long wholeInt(JsonReader json) throws IOException {
        if (json.peek() != JsonToken.NUMBER) { json.skipValue(); return Long.MIN_VALUE; }
        double value = json.nextDouble();
        return value == Math.rint(value) && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE
            ? (long)value : Long.MIN_VALUE;
    }

    private static String fileName(String server, String dimension) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((server + "\n" + dimension).getBytes(StandardCharsets.UTF_8));
            String hash = HexFormat.of().formatHex(digest, 0, 8);
            return readable(server) + "_" + readable(dimension) + "-" + hash + ".json";
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is always available", impossible);
        }
    }

    private static String readable(String value) {
        StringBuilder output = new StringBuilder(Math.min(value.length(), 40));
        for (int i = 0; i < value.length() && output.length() < 40; i++) {
            char c = Character.toLowerCase(value.charAt(i));
            output.append((c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '.' && c != '-' ? '_' : c);
        }
        return output.toString();
    }

    private static final class Bucket {
        private final Path file;
        private final String server;
        private final String dimension;
        private final LinkedHashSet<Long> chunks = new LinkedHashSet<>();
        private boolean readOnly;
        private boolean dirty;
        private volatile boolean writeFailed;
        private Bucket(Path file, String server, String dimension) {
            this.file = file; this.server = server; this.dimension = dimension;
        }
    }

}

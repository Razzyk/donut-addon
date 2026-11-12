package dev.donut.addon.expansion.utility;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/** Small local Discord IPC client; it never opens a network socket. */
public final class DonutDiscordIpc implements AutoCloseable {
    private final Transport transport;

    private DonutDiscordIpc(Transport transport) { this.transport = transport; }

    public static DonutDiscordIpc connect(String applicationId) throws IOException {
        if (applicationId == null || !applicationId.matches("[0-9]{17,22}")) {
            throw new IOException("A numeric Discord application ID is required");
        }
        IOException last = null;
        for (String endpoint : endpoints()) {
            Transport pipe = null;
            try {
                pipe = open(endpoint);
                DonutDiscordIpc ipc = new DonutDiscordIpc(pipe);
                ipc.sendFrame(0, "{\"v\":1,\"client_id\":\"" + applicationId + "\"}");
                Frame reply = ipc.readFrame();
                if (reply.opcode != 1) throw new IOException("Discord IPC did not complete its handshake");
                return ipc;
            } catch (IOException problem) {
                last = problem;
                if (pipe != null) try { pipe.close(); } catch (IOException ignored) { }
            }
        }
        throw last == null ? new IOException("No local Discord IPC endpoint found") : last;
    }

    public synchronized void setActivity(String json) throws IOException {
        String nonce = java.util.UUID.randomUUID().toString();
        sendFrame(1, "{\"cmd\":\"SET_ACTIVITY\",\"args\":{\"pid\":" + ProcessHandle.current().pid()
            + ",\"activity\":" + json + "},\"nonce\":\"" + nonce + "\"}");
        readFrame();
    }

    private void sendFrame(int opcode, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer frame = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        frame.putInt(opcode).putInt(body.length).put(body);
        transport.write(frame.array());
    }

    private Frame readFrame() throws IOException {
        ByteBuffer header = ByteBuffer.wrap(transport.read(8)).order(ByteOrder.LITTLE_ENDIAN);
        int opcode = header.getInt();
        int length = header.getInt();
        if (length < 0 || length > 1_048_576) throw new IOException("Discord IPC frame length is invalid");
        return new Frame(opcode, new String(transport.read(length), StandardCharsets.UTF_8));
    }

    @Override public void close() throws IOException { transport.close(); }

    private static Transport open(String endpoint) throws IOException {
        if (endpoint.startsWith("\\\\.\\pipe\\")) return WindowsPipe.open(endpoint);
        SocketChannel socket = SocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            socket.configureBlocking(false);
            boolean connected = socket.connect(UnixDomainSocketAddress.of(Path.of(endpoint)));
            if (!connected) {
                try (Selector selector = Selector.open()) {
                    socket.register(selector, SelectionKey.OP_CONNECT);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(IPC_TIMEOUT_SECONDS);
                    while (!socket.finishConnect()) {
                        long remaining = deadline - System.nanoTime();
                        if (remaining <= 0 || selector.select(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining))) == 0)
                            throw new java.net.SocketTimeoutException("Discord IPC connect timed out");
                        selector.selectedKeys().clear();
                    }
                }
            }
            return new UnixPipe(socket);
        } catch (IOException problem) {
            socket.close();
            throw problem;
        }
    }

    private static List<String> endpoints() {
        List<String> paths = new ArrayList<>();
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows")) {
            for (int index = 0; index < 10; index++) paths.add("\\\\.\\pipe\\discord-ipc-" + index);
            return paths;
        }
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        if (runtime != null && !runtime.isBlank()) {
            Path root = Path.of(runtime);
            paths.add(root.resolve("discord-ipc-0").toString());
            paths.add(root.resolve("app/com.discordapp.Discord/discord-ipc-0").toString());
        }
        String temp = System.getProperty("java.io.tmpdir");
        if (temp != null) paths.add(Path.of(temp).resolve("discord-ipc-0").toString());
        return paths.stream().distinct().filter(path -> path.startsWith("\\\\.\\pipe\\") || Files.exists(Path.of(path))).toList();
    }

    public static String quote(String value) {
        if (value == null) return "\"\"";
        StringBuilder out = new StringBuilder(value.length() + 8).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }

    public static byte[] frameBytes(int opcode, byte[] body) {
        ByteBuffer frame = ByteBuffer.allocate(8 + body.length).order(ByteOrder.LITTLE_ENDIAN);
        return frame.putInt(opcode).putInt(body.length).put(body).array();
    }

    private record Frame(int opcode, String payload) { }

    private static final int IPC_TIMEOUT_SECONDS = 5;

    private interface Transport extends AutoCloseable {
        void write(byte[] bytes) throws IOException;
        byte[] read(int length) throws IOException;
        @Override void close() throws IOException;
    }

    private static final class UnixPipe implements Transport {
        private final SocketChannel channel;
        private UnixPipe(SocketChannel channel) { this.channel = channel; }
        @Override public void write(byte[] bytes) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            transfer(buffer, SelectionKey.OP_WRITE);
        }
        @Override public byte[] read(int length) throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(length);
            transfer(buffer, SelectionKey.OP_READ);
            return buffer.array();
        }
        @Override public void close() throws IOException { channel.close(); }

        private void transfer(ByteBuffer buffer, int operation) throws IOException {
            try (Selector selector = Selector.open()) {
                channel.register(selector, operation);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(IPC_TIMEOUT_SECONDS);
                while (buffer.hasRemaining()) {
                    int moved = operation == SelectionKey.OP_READ ? channel.read(buffer) : channel.write(buffer);
                    if (moved < 0) throw new IOException("Discord closed the local IPC socket");
                    if (moved != 0) continue;
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0 || selector.select(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining))) == 0)
                        throw new java.net.SocketTimeoutException("Discord IPC response timed out");
                    selector.selectedKeys().clear();
                }
            }
        }
    }

    private static final class WindowsPipe implements Transport {
        private final WindowsApi api;
        private final MemorySegment handle;
        private WindowsPipe(WindowsApi api, MemorySegment handle) { this.api = api; this.handle = handle; }

        static WindowsPipe open(String path) throws IOException {
            try {
                WindowsApi api = WindowsApi.load();
                MemorySegment handle = api.open(path);
                if (handle.address() == 0L || handle.address() == -1L) throw new IOException("Discord pipe is not available");
                return new WindowsPipe(api, handle);
            } catch (IOException e) {
                throw e;
            } catch (Throwable e) {
                throw new IOException("Could not open Discord's local named pipe", e);
            }
        }

        @Override public synchronized void write(byte[] bytes) throws IOException {
            try { api.write(handle, bytes); } catch (Throwable e) { throw new IOException("Discord pipe write failed", e); }
        }
        @Override public synchronized byte[] read(int length) throws IOException {
            try { return api.read(handle, length); } catch (Throwable e) { throw new IOException("Discord pipe read failed", e); }
        }
        @Override public synchronized void close() throws IOException {
            try { api.close(handle); } catch (Throwable e) { throw new IOException("Discord pipe close failed", e); }
        }
    }

    private static final class WindowsApi {
        private static final int GENERIC_READ = 0x80000000;
        private static final int GENERIC_WRITE = 0x40000000;
        private static final int OPEN_EXISTING = 3;
        private final MethodHandle createFile;
        private final MethodHandle readFile;
        private final MethodHandle writeFile;
        private final MethodHandle peekPipe;
        private final MethodHandle closeHandle;

        private WindowsApi(SymbolLookup symbols) {
            Linker linker = Linker.nativeLinker();
            createFile = linker.downcallHandle(symbols.find("CreateFileW").orElseThrow(), FunctionDescriptor.of(
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            readFile = linker.downcallHandle(symbols.find("ReadFile").orElseThrow(), FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            writeFile = linker.downcallHandle(symbols.find("WriteFile").orElseThrow(), FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            peekPipe = linker.downcallHandle(symbols.find("PeekNamedPipe").orElseThrow(), FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            closeHandle = linker.downcallHandle(symbols.find("CloseHandle").orElseThrow(), FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        }

        static WindowsApi load() { return new WindowsApi(SymbolLookup.libraryLookup("kernel32", Arena.global())); }

        MemorySegment open(String path) throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment wide = arena.allocateFrom(path, StandardCharsets.UTF_16LE);
                return (MemorySegment) createFile.invokeExact(wide, GENERIC_READ | GENERIC_WRITE, 0,
                    MemorySegment.NULL, OPEN_EXISTING, 0, MemorySegment.NULL);
            }
        }

        void write(MemorySegment handle, byte[] bytes) throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment data = arena.allocate(bytes.length);
                data.copyFrom(MemorySegment.ofArray(bytes));
                MemorySegment count = arena.allocate(ValueLayout.JAVA_INT);
                int ok = (int) writeFile.invokeExact(handle, data, bytes.length, count, MemorySegment.NULL);
                if (ok == 0 || count.get(ValueLayout.JAVA_INT, 0) != bytes.length) throw new IOException("Discord pipe accepted a partial frame");
            }
        }

        byte[] read(MemorySegment handle, int length) throws Throwable {
            byte[] result = new byte[length];
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment data = arena.allocate(length);
                MemorySegment count = arena.allocate(ValueLayout.JAVA_INT);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(IPC_TIMEOUT_SECONDS);
                while (available(handle, arena) < length) {
                    if (System.nanoTime() >= deadline) throw new java.net.SocketTimeoutException("Discord IPC response timed out");
                    try { Thread.sleep(20L); } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Discord IPC read was interrupted", interrupted);
                    }
                }
                int ok = (int) readFile.invokeExact(handle, data, length, count, MemorySegment.NULL);
                int received = count.get(ValueLayout.JAVA_INT, 0);
                if (ok == 0 || received != length) throw new IOException("Discord pipe returned an incomplete frame");
                MemorySegment.ofArray(result).copyFrom(data.asSlice(0, length));
            }
            return result;
        }

        private int available(MemorySegment handle, Arena arena) throws Throwable {
            MemorySegment received = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment total = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment remaining = arena.allocate(ValueLayout.JAVA_INT);
            int ok = (int) peekPipe.invokeExact(handle, MemorySegment.NULL, 0, received, total, remaining);
            if (ok == 0) throw new IOException("Discord closed its named pipe");
            return total.get(ValueLayout.JAVA_INT, 0);
        }

        void close(MemorySegment handle) throws Throwable {
            if ((int) closeHandle.invokeExact(handle) == 0) throw new IOException("Windows refused to close the Discord pipe");
        }
    }
}

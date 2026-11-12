package dev.donut.addon.expansion.utility;

import net.minecraft.world.item.ItemStack;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional Windows-only text overlay for inventory privacy. It is usable only when the OS
 * confirms that its separate window is excluded from capture. Without that confirmation the
 * caller must remain in local masking mode.
 */
public final class DonutCaptureOverlay {
    private static final String WINDOW_TITLE = "Donut Addon Inventory Preview";
    private static final int DISPLAY_AFFINITY_EXCLUDE_CAPTURE = 0x11;
    private static final int GWL_EXSTYLE = -20;
    private static final int WS_EX_TRANSPARENT = 0x20;
    private static final int WS_EX_TOOLWINDOW = 0x80;
    private static final int WS_EX_LAYERED = 0x80000;
    private static final int WS_EX_NOACTIVATE = 0x08000000;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_SHOWWINDOW = 0x0040;
    private static final MemorySegment HWND_TOPMOST = MemorySegment.ofAddress(-1L);

    private static final Object LOCK = new Object();
    private static final Map<Long, String> labels = new LinkedHashMap<>();
    private static volatile OverlayFrame window;
    private static volatile MemorySegment nativeHandle = MemorySegment.NULL;
    private static volatile NativeApi nativeApi;

    private DonutCaptureOverlay() { }

    public static boolean supportedPlatform() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    public static boolean start() {
        if (!supportedPlatform()) return false;
        if (window != null && nativeHandle.address() != 0L) return true;
        final AtomicBoolean ready = new AtomicBoolean(false);
        final RuntimeException[] failure = new RuntimeException[1];
        try {
            java.awt.EventQueue.invokeAndWait(() -> {
                OverlayFrame frame = null;
                NativeApi api = null;
                MemorySegment hwnd = MemorySegment.NULL;
                try {
                    api = NativeApi.load();
                    frame = new OverlayFrame();
                    frame.setTitle(WINDOW_TITLE);
                    frame.setUndecorated(true);
                    frame.setBackground(new Color(0, 0, 0, 0));
                    frame.setAlwaysOnTop(true);
                    frame.setFocusableWindowState(false);
                    frame.setAutoRequestFocus(false);
                    frame.setIgnoreRepaint(false);
                    frame.setBounds(0, 0, 1, 1);
                    frame.setVisible(true);

                    hwnd = api.findWindow(WINDOW_TITLE);
                    if (hwnd.address() == 0L || !api.excludeFromCapture(hwnd)) {
                        frame.dispose();
                        throw new IllegalStateException("Windows did not accept capture exclusion for the overlay");
                    }
                    api.enableClickThrough(hwnd);
                    if (!api.position(hwnd, 0, 0, 1, 1)) {
                        frame.dispose();
                        throw new IllegalStateException("Windows refused to position the private overlay");
                    }
                    nativeApi = api;
                    nativeHandle = hwnd;
                    window = frame;
                    ready.set(true);
                } catch (Throwable problem) {
                    failure[0] = new RuntimeException(problem);
                    if (api != null && hwnd.address() != 0L) {
                        try { api.clearCaptureAffinity(hwnd); } catch (Throwable ignored) { }
                    }
                    if (frame != null) frame.dispose();
                }
            });
        } catch (Exception problem) {
            return false;
        }
        if (!ready.get()) {
            stop();
            return false;
        }
        return true;
    }

    public static void beginLayer(int layer) {
        synchronized (LOCK) {
            labels.keySet().removeIf(key -> (int) (key >>> 32) == layer);
        }
    }

    public static void record(int layer, int slot, ItemStack stack, int guiX, int guiY, int guiScale) {
        if (stack == null || stack.isEmpty()) return;
        String name = stack.getHoverName().getString().replaceAll("[\\r\\n\\p{Cntrl}]", " ").trim();
        if (name.isEmpty()) return;
        if (name.length() > 30) name = name.substring(0, 29) + "…";
        String text = stack.getCount() > 1 ? name + " ×" + stack.getCount() : name;
        long key = ((long) layer << 32) | (slot & 0xffffffffL);
        synchronized (LOCK) {
            labels.put(key, guiX + ":" + guiY + ":" + Math.max(guiScale, 1) + ":" + text);
        }
    }

    public static boolean present(int x, int y, int width, int height, boolean show) {
        OverlayFrame frame = window;
        MemorySegment hwnd = nativeHandle;
        NativeApi api = nativeApi;
        if (frame == null || hwnd.address() == 0L || api == null) return false;
        List<String> snapshot;
        synchronized (LOCK) {
            snapshot = new ArrayList<>(labels.values());
        }
        boolean visible = show && !snapshot.isEmpty();
        if (visible && !api.position(hwnd, x, y, Math.max(width, 1), Math.max(height, 1))) {
            stop();
            return false;
        }
        java.awt.EventQueue.invokeLater(() -> {
            if (frame != window) return;
            frame.setEntries(snapshot);
            if (visible) frame.setBounds(x, y, Math.max(width, 1), Math.max(height, 1));
            frame.setVisible(visible);
            if (visible) frame.repaint();
        });
        return true;
    }

    public static void stop() {
        synchronized (LOCK) { labels.clear(); }
        OverlayFrame current = window;
        window = null;
        nativeHandle = MemorySegment.NULL;
        nativeApi = null;
        if (current != null) {
            java.awt.EventQueue.invokeLater(current::dispose);
        }
    }

    public static void hide() {
        OverlayFrame frame = window;
        if (frame != null) java.awt.EventQueue.invokeLater(() -> { if (frame == window) frame.setVisible(false); });
    }

    private static final class OverlayFrame extends Frame {
        private final OverlayCanvas canvas = new OverlayCanvas();

        private OverlayFrame() {
            add(canvas);
            setLayout(null);
            canvas.setBounds(0, 0, 1, 1);
        }

        @Override
        public void setBounds(int x, int y, int width, int height) {
            super.setBounds(x, y, width, height);
            if (canvas != null) canvas.setBounds(0, 0, width, height);
        }

        void setEntries(List<String> entries) { canvas.entries = List.copyOf(entries); }
    }

    private static final class OverlayCanvas extends Canvas {
        private volatile List<String> entries = List.of();

        @Override
        public void paint(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
                for (String entry : entries) {
                    String[] parts = entry.split(":", 4);
                    if (parts.length != 4) continue;
                    try {
                        int x = Integer.parseInt(parts[0]) * Integer.parseInt(parts[2]);
                        int y = Integer.parseInt(parts[1]) * Integer.parseInt(parts[2]);
                        String text = parts[3];
                        int width = Math.min(g.getFontMetrics().stringWidth(text) + 6, 190);
                        int px = Math.max(0, Math.min(x + 16, getWidth() - width));
                        int py = Math.max(12, Math.min(y + 25, getHeight() - 2));
                        g.setColor(new Color(8, 10, 13, 225));
                        g.fillRoundRect(px, py - 11, width, 14, 4, 4);
                        g.setColor(Color.WHITE);
                        g.drawString(text, px + 3, py);
                    } catch (NumberFormatException ignored) {
                        // A stale coordinate cannot make the overlay fail closed.
                    }
                }
            } finally {
                g.dispose();
            }
        }
    }

    private static final class NativeApi {
        private final MethodHandle findWindow;
        private final MethodHandle setAffinity;
        private final MethodHandle getWindowLong;
        private final MethodHandle setWindowLong;
        private final MethodHandle setWindowPos;

        private NativeApi(SymbolLookup user32) {
            Linker linker = Linker.nativeLinker();
            findWindow = linker.downcallHandle(user32.find("FindWindowW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            setAffinity = linker.downcallHandle(user32.find("SetWindowDisplayAffinity").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            getWindowLong = linker.downcallHandle(user32.find("GetWindowLongPtrW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            setWindowLong = linker.downcallHandle(user32.find("SetWindowLongPtrW").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            setWindowPos = linker.downcallHandle(user32.find("SetWindowPos").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        }

        static NativeApi load() {
            return new NativeApi(SymbolLookup.libraryLookup("user32", Arena.global()));
        }

        MemorySegment findWindow(String title) throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment wideTitle = arena.allocateFrom(title, StandardCharsets.UTF_16LE);
                return (MemorySegment) findWindow.invokeExact(MemorySegment.NULL, wideTitle);
            }
        }

        boolean excludeFromCapture(MemorySegment hwnd) throws Throwable {
            return (int) setAffinity.invokeExact(hwnd, DISPLAY_AFFINITY_EXCLUDE_CAPTURE) != 0;
        }

        void clearCaptureAffinity(MemorySegment hwnd) throws Throwable {
            setAffinity.invokeExact(hwnd, 0);
        }

        void enableClickThrough(MemorySegment hwnd) throws Throwable {
            MemorySegment old = (MemorySegment) getWindowLong.invokeExact(hwnd, GWL_EXSTYLE);
            long flags = old.address() | WS_EX_TRANSPARENT | WS_EX_TOOLWINDOW | WS_EX_LAYERED | WS_EX_NOACTIVATE;
            setWindowLong.invokeExact(hwnd, GWL_EXSTYLE, MemorySegment.ofAddress(flags));
        }

        boolean position(MemorySegment hwnd, int x, int y, int width, int height) {
            try {
                return (int) setWindowPos.invokeExact(hwnd, HWND_TOPMOST, x, y, width, height, SWP_NOACTIVATE | SWP_SHOWWINDOW) != 0;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }
}

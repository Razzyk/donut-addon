package dev.donut.addon.security;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;

/**
 * Read-only, bounded checks for the published SilentNET indicators.
 * The time budget is cooperative between local reads; a blocked filesystem call cannot be interrupted by this scanner.
 */
public final class SilentNetScanner {
    /** SHA-256 hashes published for the loader and the eleven trojanized client archives. */
    public static final Set<String> KNOWN_SAMPLE_SHA256 = Set.of(
        "d707e48f44fb0580201b7878b0c3e707b6f0629b7d927cc9f03dd574c49556ef",
        "be181fda1892fb98a22967486f153b70b2435c01c88229070b1a830c7620d0c7",
        "6936dc93d7ff405bdb88e808f91330a18326de46da7a9bd1599c0666c0021fd8",
        "2be8883b922700fb70e170b76a8892ff16df3cd98da1c157d41694699d9bfd7d",
        "0b1ecf0c0fe1a9b12bb069648e1b3c758e7187c3bceb0097bcaea3f2ea61640f",
        "6852a8d5b1f77d33c61f781227372f94e889bfc18b767519bc51341483857660",
        "216280939f26f9860c0c1b0f426582bfa79dd2fe34bddac726b6e718b99f23b6",
        "f8d4565070892bf89d86c72e0b5b6eed4ef1b3615e47df246f57db43e187b794",
        "64ae4dc18d3ff543bac7bb4eea180e2bcd438fd2cb573905d1fe3ad40bc6e6e9",
        "29cea1720479b5e7e692d4e1021c863179f3b8cfea0a7133df0aa9dc7cb9570d",
        "b392cfe229033d34ac873353e6ccabfbe83f0fdc3d325dbeb61ffdb26576fdf7",
        "a368f5dbaf842d673adcc1dee6805bdb415b30cf7301550fd84949f73f02c526"
    );

    private static final Limits DEFAULT_LIMITS = new Limits(
        1024,
        128L * 1024 * 1024,
        1024L * 1024 * 1024,
        Duration.ofSeconds(15),
        32_768,
        4L * 1024 * 1024,
        512 * 1024
    );

    private static final String LOADER_ENTRY = "net/fabric/ygkjfk8.class";
    private static final byte[] CLASS_MAGIC = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
    private static final byte[] RESTART_MARKER = "-restarted".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final int HASH_BUFFER_SIZE = 64 * 1024;
    private static final int ZIP_EOCD_FIXED_BYTES = 22;
    private static final int MAX_ZIP_COMMENT_BYTES = 65_535;
    private static final long ZIP_EOCD_SIGNATURE = 0x06054b50L;
    private static final long ZIP32_SENTINEL = 0xffff_ffffL;
    private static final int ZIP16_SENTINEL = 0xffff;

    private SilentNetScanner() {
    }

    /** Scans direct JAR children of {@code modsDirectory}, supplied JAR origins, and the paired local Windows footprint. */
    public static ScanResult scan(Path modsDirectory, List<Path> loadedModOrigins, Path localAppData) {
        return scanWithOptions(modsDirectory, loadedModOrigins, localAppData, KNOWN_SAMPLE_SHA256, DEFAULT_LIMITS);
    }

    /** Package-private test seam for harmless fixtures and small resource limits. */
    static ScanResult scanWithOptions(Path modsDirectory, List<Path> loadedModOrigins, Path localAppData,
                                      Set<String> exactHashes, Limits limits) {
        Set<String> hashes = normalizeHashes(exactHashes);
        Limits checkedLimits = limits == null ? DEFAULT_LIMITS : limits;
        ScanContext context = new ScanContext(checkedLimits);
        List<Finding> findings = new ArrayList<>();
        // Cheap, strong host evidence runs first so a large modpack cannot starve it.
        scanHostFootprint(localAppData, context, findings);
        LinkedHashMap<Path, Path> candidates = collectCandidates(modsDirectory, loadedModOrigins, context);

        int candidateIndex = 0;
        for (Map.Entry<Path, Path> candidate : candidates.entrySet()) {
            if (!context.isActive()) {
                context.skip(candidates.size() - candidateIndex);
                break;
            }
            candidateIndex++;
            try {
                scanArchive(candidate.getValue(), hashes, context, findings);
            } catch (ScanStoppedException stopped) {
                context.skip(candidates.size() - candidateIndex + 1);
                break;
            } catch (IOException | RuntimeException failure) {
                context.skip(1);
            }
        }

        if (!context.isActive()) {
            context.skip(1);
        }
        return new ScanResult(findings, context.skippedCount);
    }

    private static LinkedHashMap<Path, Path> collectCandidates(Path modsDirectory, List<Path> loadedModOrigins,
                                                                ScanContext context) {
        LinkedHashMap<Path, Path> candidates = new LinkedHashMap<>();
        if (modsDirectory == null) {
            context.skip(1);
        } else if (Files.isSymbolicLink(modsDirectory)) {
            context.skip(1);
        } else if (!Files.isDirectory(modsDirectory, LinkOption.NOFOLLOW_LINKS)) {
            context.skip(1);
        } else {
            try (DirectoryStream<Path> children = Files.newDirectoryStream(modsDirectory)) {
                for (Path child : children) {
                    if (!context.isActive()) {
                        context.skip(1);
                        break;
                    }
                    if (!isJarPath(child)) {
                        continue;
                    }
                    if (!addCandidate(candidates, child, context)) {
                        break;
                    }
                }
            } catch (IOException | RuntimeException failure) {
                context.skip(1);
            }
        }

        if (loadedModOrigins != null) {
            for (Path origin : loadedModOrigins) {
                if (!context.isActive()) {
                    context.skip(1);
                    break;
                }
                if (origin == null) {
                    context.skip(1);
                    continue;
                }
                if (isJarPath(origin) && !addCandidate(candidates, origin, context)) {
                    break;
                }
            }
        }
        return candidates;
    }

    private static boolean addCandidate(LinkedHashMap<Path, Path> candidates, Path path, ScanContext context) {
        Path normalized;
        try {
            normalized = path.toAbsolutePath().normalize();
        } catch (RuntimeException failure) {
            context.skip(1);
            return true;
        }
        if (candidates.containsKey(normalized)) {
            return true;
        }
        if (candidates.size() >= context.limits.maxFiles) {
            context.skip(1);
            return false;
        }
        candidates.put(normalized, path);
        return true;
    }

    private static boolean isJarPath(Path path) {
        Path name = path.getFileName();
        return name != null && name.toString().toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private static void scanArchive(Path path, Set<String> exactHashes, ScanContext context,
                                    List<Finding> findings) throws IOException {
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.isSymbolicLink()) {
            context.skip(1);
            return;
        }
        long size = before.size();
        if (size < 0 || size > context.limits.maxBytesPerFile) {
            context.skip(1);
            return;
        }

        ZipBounds bounds = preflightArchive(path, context);
        if (bounds == null) {
            context.skip(1);
            return;
        }
        if (!context.reserveArchiveBytes(size)) {
            context.skip(1);
            return;
        }
        String hash = sha256(path, size, before, context);
        if (hash == null) {
            context.skip(1);
            return;
        }
        if (exactHashes.contains(hash)) {
            findings.add(new Finding(FindingKind.KNOWN_ARCHIVE_HASH,
                "A mod archive matches a published SilentNET-associated sample hash.", safeBasename(path)));
            return;
        }
        boolean loaderFindingAdded = false;
        try (ZipFile zip = new ZipFile(path.toFile(), ZipFile.OPEN_READ)) {
            if (!context.isActive()) {
                throw new ScanStoppedException();
            }
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int inspectedEntries = 0;
            while (entries.hasMoreElements()) {
                if (!context.isActive()) {
                    throw new ScanStoppedException();
                }
                ZipEntry entry = entries.nextElement();
                if (++inspectedEntries > context.limits.maxArchiveEntries) {
                    context.skip(1);
                    break;
                }
                if (!LOADER_ENTRY.equals(entry.getName()) || entry.isDirectory()) {
                    continue;
                }
                byte[] classBytes = readClassEntry(zip, entry, context);
                if (classBytes == null) {
                    context.skip(1);
                    continue;
                }
                if (!loaderFindingAdded && startsWith(classBytes, CLASS_MAGIC)
                    && contains(classBytes, RESTART_MARKER)) {
                    String internalName;
                    try {
                        // Parse class metadata only. Never define or execute the archive class.
                        internalName = new ClassReader(classBytes).getClassName();
                    } catch (RuntimeException malformedClass) {
                        context.skip(1);
                        continue;
                    }
                    if (!"net/fabric/ygkjfk8".equals(internalName)) {
                        continue;
                    }
                    findings.add(new Finding(FindingKind.SUSPICIOUS_LOADER_CLASS,
                        "A mod archive contains the reported loader class with the exact restart marker.",
                        safeBasename(path) + ": " + LOADER_ENTRY));
                    loaderFindingAdded = true;
                }
            }
            if (inspectedEntries != bounds.entryCount) {
                context.skip(1);
            }
        }

        if (!context.isActive()) {
            context.skip(1);
        }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || after.isSymbolicLink() || after.size() != size
            || !after.lastModifiedTime().equals(before.lastModifiedTime())) {
            context.skip(1);
        }
    }

    /** Bounds the ZIP central directory before ZipFile allocates or enumerates it. */
    private static ZipBounds preflightArchive(Path path, ScanContext context) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        long fileSize = attributes.size();
        if (!attributes.isRegularFile() || attributes.isSymbolicLink()
            || fileSize < ZIP_EOCD_FIXED_BYTES || fileSize > context.limits.maxBytesPerFile) {
            return null;
        }

        int tailLength = (int) Math.min(fileSize, ZIP_EOCD_FIXED_BYTES + MAX_ZIP_COMMENT_BYTES);
        if (!context.reserveArchiveBytes(tailLength)) {
            return null;
        }
        byte[] tail = new byte[tailLength];
        long tailOffset = fileSize - tailLength;
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer target = ByteBuffer.wrap(tail);
            long position = tailOffset;
            while (target.hasRemaining()) {
                if (!context.isActive()) {
                    throw new ScanStoppedException();
                }
                int count = channel.read(target, position);
                if (count < 0) {
                    return null;
                }
                if (count == 0) {
                    continue;
                }
                position += count;
            }
        }

        for (int offset = tail.length - ZIP_EOCD_FIXED_BYTES; offset >= 0; offset--) {
            if (unsignedInt(tail, offset) != ZIP_EOCD_SIGNATURE) {
                continue;
            }
            ByteBuffer eocd = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN);
            int diskNumber = Short.toUnsignedInt(eocd.getShort(offset + 4));
            int centralDirectoryDisk = Short.toUnsignedInt(eocd.getShort(offset + 6));
            int entriesOnDisk = Short.toUnsignedInt(eocd.getShort(offset + 8));
            int entryCount = Short.toUnsignedInt(eocd.getShort(offset + 10));
            long directorySize = Integer.toUnsignedLong(eocd.getInt(offset + 12));
            long directoryOffset = Integer.toUnsignedLong(eocd.getInt(offset + 16));
            int commentLength = Short.toUnsignedInt(eocd.getShort(offset + 20));
            long absoluteEocdOffset = tailOffset + offset;

            if (offset + ZIP_EOCD_FIXED_BYTES + commentLength != tail.length
                || diskNumber != 0 || centralDirectoryDisk != 0 || entriesOnDisk != entryCount
                || entriesOnDisk == ZIP16_SENTINEL || entryCount == ZIP16_SENTINEL
                || directorySize == ZIP32_SENTINEL || directoryOffset == ZIP32_SENTINEL
                || entryCount > context.limits.maxArchiveEntries
                || directorySize > context.limits.maxCentralDirectoryBytes
                || directoryOffset + directorySize > absoluteEocdOffset) {
                continue;
            }
            if (!context.reserveArchiveBytes(directorySize)) {
                continue;
            }
            return new ZipBounds(entryCount);
        }
        return null;
    }

    private static long unsignedInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL)
            | ((bytes[offset + 1] & 0xffL) << 8)
            | ((bytes[offset + 2] & 0xffL) << 16)
            | ((bytes[offset + 3] & 0xffL) << 24);
    }

    private static String sha256(Path path, long expectedSize, BasicFileAttributes before,
                                 ScanContext context) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }

        long read = 0;
        byte[] buffer = new byte[HASH_BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            while (read < expectedSize) {
                if (!context.isActive()) {
                    throw new ScanStoppedException();
                }
                int wanted = (int) Math.min(buffer.length, expectedSize - read);
                int count = input.read(buffer, 0, wanted);
                if (count < 0) {
                    return null;
                }
                if (count == 0) {
                    continue;
                }
                digest.update(buffer, 0, count);
                read += count;
            }
            if (input.read() != -1) {
                return null;
            }
        }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || after.isSymbolicLink() || after.size() != expectedSize
            || !after.lastModifiedTime().equals(before.lastModifiedTime())) {
            return null;
        }
        return hex(digest.digest());
    }

    private static byte[] readClassEntry(ZipFile zip, ZipEntry entry, ScanContext context) throws IOException {
        long size = entry.getSize();
        long compressedSize = entry.getCompressedSize();
        if (size < 0 || size > context.limits.maxClassBytes
            || compressedSize < 0 || compressedSize > context.limits.maxClassBytes
            || size + compressedSize < size || !context.reserveClassBytes(size + compressedSize)) {
            return null;
        }
        byte[] bytes = new byte[(int) size];
        int offset = 0;
        try (InputStream input = zip.getInputStream(entry)) {
            while (offset < bytes.length) {
                if (!context.isActive()) {
                    throw new ScanStoppedException();
                }
                int count = input.read(bytes, offset, bytes.length - offset);
                if (count < 0) {
                    return null;
                }
                if (count == 0) {
                    continue;
                }
                offset += count;
            }
            if (input.read() != -1) {
                return null;
            }
        }
        return bytes;
    }

    private static void scanHostFootprint(Path localAppData, ScanContext context, List<Finding> findings) {
        if (!context.isActive()) {
            context.skip(1);
            return;
        }
        if (localAppData == null) {
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                context.skip(1);
            }
            return;
        }

        Path appData;
        try {
            appData = localAppData.toAbsolutePath().normalize();
        } catch (RuntimeException failure) {
            context.skip(1);
            return;
        }
        Path microsoft = appData.resolve("Microsoft");
        Path windows = microsoft.resolve("Windows");
        Path profile = windows.resolve("NtProfileIndex");
        Path appHost = profile.resolve("AppHost");
        Path management = windows.resolve("IManagementEngine");

        Path[] directories = {appData, microsoft, windows, profile, appHost, management};
        for (Path directory : directories) {
            if (!context.isActive()) {
                context.skip(1);
                return;
            }
            PathState state = state(directory);
            if (state == PathState.SYMLINK || state == PathState.ERROR) {
                context.skip(1);
                return;
            }
            if (state == PathState.MISSING) {
                return;
            }
            if (state != PathState.DIRECTORY) {
                return;
            }
        }

        Path[] files = {
            profile.resolve("python.exe"),
            profile.resolve("python312.dll"),
            appHost.resolve("main.py"),
            management.resolve("python.exe"),
            management.resolve("get-pip.py")
        };
        for (Path file : files) {
            if (!context.isActive()) {
                context.skip(1);
                return;
            }
            PathState state = state(file);
            if (state == PathState.SYMLINK || state == PathState.ERROR) {
                context.skip(1);
                return;
            }
            if (state == PathState.MISSING) {
                return;
            }
            if (state != PathState.REGULAR_FILE) {
                return;
            }
        }
        findings.add(new Finding(FindingKind.PAIRED_STAGING_FILES,
            "A paired Windows Python staging layout matches the reported SilentNET host footprint.",
            "Windows app data staging layout"));
    }

    private static PathState state(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()) {
                return PathState.SYMLINK;
            }
            if (attributes.isDirectory()) {
                return PathState.DIRECTORY;
            }
            if (attributes.isRegularFile()) {
                return PathState.REGULAR_FILE;
            }
            return PathState.OTHER;
        } catch (NoSuchFileException | NotDirectoryException missing) {
            return PathState.MISSING;
        } catch (IOException | SecurityException failure) {
            return PathState.ERROR;
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(byte[] bytes, byte[] needle) {
        if (needle.length == 0 || bytes.length < needle.length) {
            return false;
        }
        outer:
        for (int i = 0; i <= bytes.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(Character.forDigit((value >>> 4) & 0xF, 16));
            result.append(Character.forDigit(value & 0xF, 16));
        }
        return result.toString();
    }

    private static Set<String> normalizeHashes(Set<String> hashes) {
        if (hashes == null || hashes.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String hash : hashes) {
            if (hash != null && hash.matches("(?i)[0-9a-f]{64}")) {
                normalized.add(hash.toLowerCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static String safeBasename(Path path) {
        Path fileName = path.getFileName();
        String name = fileName == null ? "mod.jar" : fileName.toString();
        String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank()) {
            return "mod.jar";
        }
        return safe.length() <= 96 ? safe : safe.substring(0, 96);
    }

    private static final class ScanContext {
        private final Limits limits;
        private final long startedAt = System.nanoTime();
        private long archiveBytes;
        private long classBytes;
        private int skippedCount;

        private ScanContext(Limits limits) {
            this.limits = limits;
        }

        private boolean reserveArchiveBytes(long bytes) {
            if (bytes > limits.maxTotalBytes - archiveBytes - classBytes) {
                return false;
            }
            archiveBytes += bytes;
            return true;
        }

        private boolean reserveClassBytes(long bytes) {
            if (bytes > limits.maxTotalBytes - archiveBytes - classBytes) {
                return false;
            }
            classBytes += bytes;
            return true;
        }

        private boolean isActive() {
            return !Thread.currentThread().isInterrupted()
                && System.nanoTime() - startedAt < limits.maxDuration.toNanos();
        }

        private void skip(int count) {
            if (count <= 0) {
                return;
            }
            skippedCount = (int) Math.min(Integer.MAX_VALUE, (long) skippedCount + count);
        }
    }

    private enum PathState {
        MISSING,
        DIRECTORY,
        REGULAR_FILE,
        OTHER,
        SYMLINK,
        ERROR
    }

    private static final class ScanStoppedException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    private record ZipBounds(int entryCount) {
    }

    public enum FindingKind {
        KNOWN_ARCHIVE_HASH,
        SUSPICIOUS_LOADER_CLASS,
        PAIRED_STAGING_FILES
    }

    /** A safe, concise indicator; locations never contain parent directories. */
    public record Finding(FindingKind kind, String description, String location) {
        public Finding {
            if (kind == null || description == null || location == null) {
                throw new IllegalArgumentException("finding fields must be non-null");
            }
        }
    }

    /** Immutable scan output. A nonzero skipped count means the scan cannot be treated as clean. */
    public record ScanResult(List<Finding> findings, int skippedCount) {
        public ScanResult {
            findings = List.copyOf(findings);
            if (skippedCount < 0) {
                throw new IllegalArgumentException("skippedCount must not be negative");
            }
        }

        public boolean incomplete() {
            return skippedCount > 0;
        }
    }

    /** Package-private injectable bounds used only by fixture tests. */
    static record Limits(int maxFiles, long maxBytesPerFile, long maxTotalBytes,
                         Duration maxDuration, int maxArchiveEntries, long maxCentralDirectoryBytes,
                         int maxClassBytes) {
        Limits {
            if (maxFiles < 1 || maxBytesPerFile < 1 || maxTotalBytes < 1
                || maxDuration == null || maxDuration.isNegative() || maxDuration.isZero()
                || maxArchiveEntries < 1 || maxCentralDirectoryBytes < 1 || maxClassBytes < 4) {
                throw new IllegalArgumentException("invalid scan limits");
            }
        }
    }
}

package dev.donut.addon.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SilentNetScannerTest {
    @TempDir
    Path tempDir;

    @Test
    void publishedHashesAreExactSha256Values() {
        assertEquals(12, SilentNetScanner.KNOWN_SAMPLE_SHA256.size());
        assertTrue(SilentNetScanner.KNOWN_SAMPLE_SHA256.stream().allMatch(hash -> hash.matches("[0-9a-f]{64}")));
    }

    @Test
    void exactHashFixtureProducesAnImmutableBasenameOnlyFinding() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("profile-secret/.minecraft/mods"));
        Path archive = createJar(mods.resolve("sample.jar"), Map.of("readme.txt", bytes("ordinary archive")));
        String fixtureHash = sha256(Files.readAllBytes(archive));

        SilentNetScanner.ScanResult result = SilentNetScanner.scanWithOptions(
            mods, List.of(), tempDir, Set.of(fixtureHash), defaultLimits());

        assertEquals(1, result.findings().size());
        SilentNetScanner.Finding finding = result.findings().getFirst();
        assertEquals(SilentNetScanner.FindingKind.KNOWN_ARCHIVE_HASH, finding.kind());
        assertEquals("sample.jar", finding.location());
        assertFalse(finding.location().contains("profile-secret"));
        assertFalse(result.incomplete());
        assertThrows(UnsupportedOperationException.class,
            () -> result.findings().add(finding));
    }

    @Test
    void eocdLikeBytesInZipCommentDoNotHideTheRealEocd() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("mods"));
        Path archive = createJar(mods.resolve("commented.jar"), Map.of(
            "net/fabric/ygkjfk8.class", classFile(true, true, "net/fabric/ygkjfk8")
        ));
        appendEocdLikeZipComment(archive);

        SilentNetScanner.ScanResult result = scan(mods, tempDir);

        assertEquals(1, result.findings().size());
        assertEquals("commented.jar: net/fabric/ygkjfk8.class", result.findings().getFirst().location());
        assertFalse(result.incomplete());
    }

    @Test
    void benignNamesAndLooseFamilyStringsDoNotMatch() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("mods"));
        createJar(mods.resolve("SilentNET-Polygon-Python.jar"), Map.of(
            "notes.txt", bytes("SilentNET Polygon Python -restarted polygon.drpc.org"),
            "net/fabric/ygkjfk8.class", classFile(true, true, "net/fabric/ordinary")
        ));

        SilentNetScanner.ScanResult result = scan(mods, tempDir);

        assertTrue(result.findings().isEmpty());
        assertFalse(result.incomplete());
    }

    @Test
    void loaderMarkerRequiresExactEntryClassMagicAndRestartString() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("mods"));
        createJar(mods.resolve("marker-only.jar"), Map.of(
            "net/fabric/ygkjfk8.class", classFile(true, false, "net/fabric/ygkjfk8")
        ));
        createJar(mods.resolve("restart-only.jar"), Map.of(
            "net/fabric/ygkjfk8.class", classFile(false, true, "net/fabric/ygkjfk8")
        ));
        createJar(mods.resolve("wrong-entry.jar"), Map.of(
            "net/fabric/other.class", classFile(true, true, "net/fabric/ygkjfk8")
        ));
        createJar(mods.resolve("matching.jar"), Map.of(
            "net/fabric/ygkjfk8.class", classFile(true, true, "net/fabric/ygkjfk8")
        ));

        SilentNetScanner.ScanResult result = scan(mods, tempDir);

        assertEquals(1, result.findings().size());
        assertEquals("matching.jar: net/fabric/ygkjfk8.class", result.findings().getFirst().location());
        assertFalse(result.incomplete());
    }

    @Test
    void validLoaderClassMustHaveTheExactInternalNameAndMalformedClassesAreIncomplete() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("mods"));
        createJar(mods.resolve("wrong-internal-name.jar"), Map.of(
            "net/fabric/ygkjfk8.class", classFile(true, true, "net/fabric/another")
        ));
        createJar(mods.resolve("malformed-class.jar"), Map.of(
            "net/fabric/ygkjfk8.class", bytesWithMagicAndMarker()
        ));

        SilentNetScanner.ScanResult result = scan(mods, tempDir);

        assertTrue(result.findings().isEmpty());
        assertTrue(result.incomplete());
    }

    @Test
    void matchingLoaderClassProducesOneFindingEvenWhenDuplicateEntriesExist() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("mods"));
        Path archive = mods.resolve("review-this.jar");
        createJarWithDuplicateEntries(archive,
            "net/fabric/ygkjfk8.class", classFile(true, false, "net/fabric/ygkjfk8"),
            "net/fabric/ygkjfkX.class", classFile(true, true, "net/fabric/ygkjfk8"));

        SilentNetScanner.ScanResult result = scan(mods, tempDir);

        assertEquals(1, result.findings().size());
        SilentNetScanner.Finding finding = result.findings().getFirst();
        assertEquals(SilentNetScanner.FindingKind.SUSPICIOUS_LOADER_CLASS, finding.kind());
        assertEquals("review-this.jar: net/fabric/ygkjfk8.class", finding.location());
        assertFalse(result.incomplete());
    }

    @Test
    void pairedHostFootprintRequiresEveryReportedFile() throws Exception {
        Path localAppData = Files.createDirectories(tempDir.resolve("local-app-data"));
        Path windows = Files.createDirectories(localAppData.resolve("Microsoft/Windows"));
        Path profile = Files.createDirectories(windows.resolve("NtProfileIndex/AppHost"));
        Files.createFile(profile.getParent().resolve("python.exe"));
        Files.createFile(profile.getParent().resolve("python312.dll"));
        Files.createFile(profile.resolve("main.py"));

        SilentNetScanner.ScanResult partial = scan(Files.createDirectories(tempDir.resolve("empty-mods")), localAppData);
        assertTrue(partial.findings().isEmpty());
        assertFalse(partial.incomplete());

        Path management = Files.createDirectories(windows.resolve("IManagementEngine"));
        Files.createFile(management.resolve("python.exe"));
        Files.createFile(management.resolve("get-pip.py"));
        SilentNetScanner.ScanResult complete = scan(Files.createDirectories(tempDir.resolve("empty-mods-2")), localAppData);

        assertEquals(1, complete.findings().size());
        assertEquals(SilentNetScanner.FindingKind.PAIRED_STAGING_FILES, complete.findings().getFirst().kind());
        assertEquals("Windows app data staging layout", complete.findings().getFirst().location());
        assertFalse(complete.incomplete());
    }

    @Test
    void corruptJarIsReportedAsSkippedAndDoesNotHideAnotherCandidate() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("mods"));
        Files.writeString(mods.resolve("corrupt.jar"), "not a zip archive");
        Path matching = createJar(mods.resolve("matching.jar"), Map.of(
            "net/fabric/ygkjfk8.class", classFile(true, true, "net/fabric/ygkjfk8")
        ));

        SilentNetScanner.ScanResult result = SilentNetScanner.scan(mods, List.of(matching, matching), tempDir);

        assertEquals(1, result.findings().size());
        assertEquals("matching.jar: net/fabric/ygkjfk8.class", result.findings().getFirst().location());
        assertEquals(1, result.skippedCount());
        assertTrue(result.incomplete());
    }

    @Test
    void fileCountByteAndCentralDirectoryLimitsMarkTheScanIncomplete() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("mods"));
        Path first = createJar(mods.resolve("first.jar"), Map.of("a.txt", bytes("a")));
        createJar(mods.resolve("second.jar"), Map.of("b.txt", bytes("b")));

        SilentNetScanner.ScanResult fileLimit = scanWithLimits(mods, 1, 1_000_000, 1_000_000, 4096, 4_000_000, 512_000);
        assertEquals(1, fileLimit.skippedCount());
        assertTrue(fileLimit.incomplete());

        SilentNetScanner.ScanResult byteLimit = scanWithLimits(mods, 10, 1_000_000, 16, 4096, 4_000_000, 512_000);
        assertTrue(byteLimit.incomplete());
        assertTrue(byteLimit.skippedCount() >= 1);

        SilentNetScanner.ScanResult directoryLimit = scanWithLimits(mods, 10, 1_000_000, 1_000_000, 1, 1, 512_000);
        assertTrue(directoryLimit.incomplete());
        assertTrue(directoryLimit.skippedCount() >= 1);

        Path classMods = Files.createDirectories(tempDir.resolve("class-mods"));
        createJar(classMods.resolve("target.jar"), Map.of(
            "net/fabric/ygkjfk8.class", classFile(true, true, "net/fabric/ygkjfk8")
        ));
        SilentNetScanner.ScanResult classLimit = scanWithLimits(classMods, 10, 1_000_000, 1_000_000,
            4096, 4_000_000, 16);
        assertTrue(classLimit.incomplete());
        assertTrue(classLimit.findings().isEmpty());
        assertTrue(Files.size(first) > 0);
    }

    @Test
    void elapsedDeadlineMarksUnscannedWorkIncomplete() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("mods"));
        createJar(mods.resolve("candidate.jar"), Map.of("ordinary.txt", bytes("nothing special")));
        SilentNetScanner.Limits expired = new SilentNetScanner.Limits(10, 1_000_000, 1_000_000,
            Duration.ofNanos(1), 4096, 4_000_000, 512_000);

        SilentNetScanner.ScanResult result = SilentNetScanner.scanWithOptions(mods, List.of(), tempDir, Set.of(), expired);

        assertTrue(result.incomplete());
    }

    @Test
    void preInterruptedScanIsIncompleteAndLeavesInterruptSet() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("mods"));
        createJar(mods.resolve("candidate.jar"), Map.of("ordinary.txt", bytes("nothing special")));
        Thread.currentThread().interrupt();
        try {
            SilentNetScanner.ScanResult result = scan(mods, tempDir);
            assertTrue(result.incomplete());
            assertTrue(result.skippedCount() > 0);
        } finally {
            assertTrue(Thread.currentThread().isInterrupted());
            Thread.interrupted();
        }
    }

    @Test
    void scanDoesNotModifyArchiveOrHostFixtureFiles() throws Exception {
        Path mods = Files.createDirectories(tempDir.resolve("mods"));
        Path archive = createJar(mods.resolve("unchanged.jar"), Map.of(
            "net/fabric/ygkjfk8.class", classFile(true, true, "net/fabric/ygkjfk8")
        ));
        byte[] beforeBytes = Files.readAllBytes(archive);
        FileTime beforeTime = Files.getLastModifiedTime(archive);
        Path hostFile = Files.createDirectories(tempDir.resolve("local/Microsoft/Windows/NtProfileIndex"))
            .resolve("python.exe");
        Files.createFile(hostFile);
        byte[] hostBefore = Files.readAllBytes(hostFile);
        FileTime hostTime = Files.getLastModifiedTime(hostFile);

        scan(mods, tempDir.resolve("local"));

        assertArrayEquals(beforeBytes, Files.readAllBytes(archive));
        assertEquals(beforeTime, Files.getLastModifiedTime(archive));
        assertArrayEquals(hostBefore, Files.readAllBytes(hostFile));
        assertEquals(hostTime, Files.getLastModifiedTime(hostFile));
    }

    private SilentNetScanner.ScanResult scan(Path mods, Path localAppData) {
        return SilentNetScanner.scanWithOptions(mods, List.of(), localAppData, Set.of(), defaultLimits());
    }

    private SilentNetScanner.ScanResult scanWithLimits(Path mods, int maxFiles, long maxFileBytes,
                                                        long maxTotalBytes, int maxEntries,
                                                        long maxDirectoryBytes, int maxClassBytes) {
        SilentNetScanner.Limits limits = new SilentNetScanner.Limits(maxFiles, maxFileBytes, maxTotalBytes,
            Duration.ofSeconds(5), maxEntries, maxDirectoryBytes, maxClassBytes);
        return SilentNetScanner.scanWithOptions(mods, List.of(), tempDir, Set.of(), limits);
    }

    private static SilentNetScanner.Limits defaultLimits() {
        return new SilentNetScanner.Limits(1024, 128L * 1024 * 1024, 1024L * 1024 * 1024,
            Duration.ofSeconds(15), 32_768, 4L * 1024 * 1024, 512 * 1024);
    }

    private static Path createJar(Path path, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return path;
    }

    private static void appendEocdLikeZipComment(Path path) throws IOException {
        byte[] archive = Files.readAllBytes(path);
        int eocd = -1;
        for (int offset = archive.length - 22; offset >= 0; offset--) {
            if ((archive[offset] & 0xff) == 0x50 && (archive[offset + 1] & 0xff) == 0x4b
                && (archive[offset + 2] & 0xff) == 0x05 && (archive[offset + 3] & 0xff) == 0x06) {
                eocd = offset;
                break;
            }
        }
        if (eocd < 0) {
            throw new IOException("fixture has no EOCD record");
        }
        byte[] comment = new byte[22];
        comment[0] = 0x50;
        comment[1] = 0x4b;
        comment[2] = 0x05;
        comment[3] = 0x06;
        comment[20] = 1; // This signature-like candidate's comment length cannot reach EOF.
        archive[eocd + 20] = 22;
        archive[eocd + 21] = 0;
        byte[] withComment = new byte[archive.length + comment.length];
        System.arraycopy(archive, 0, withComment, 0, archive.length);
        System.arraycopy(comment, 0, withComment, archive.length, comment.length);
        Files.write(path, withComment);
    }

    private static Path createJarWithDuplicateEntries(Path path, String firstName, byte[] first,
                                                       String secondName, byte[] second) throws IOException {
        if (firstName.length() != secondName.length() || firstName.equals(secondName)) {
            throw new IllegalArgumentException("duplicate fixture names must be distinct and equal length");
        }
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry(firstName));
            zip.write(first);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry(secondName));
            zip.write(second);
            zip.closeEntry();
        }
        byte[] archive = Files.readAllBytes(path);
        byte[] needle = secondName.getBytes(StandardCharsets.UTF_8);
        byte[] replacement = firstName.getBytes(StandardCharsets.UTF_8);
        int replacements = 0;
        for (int offset = 0; offset <= archive.length - needle.length; offset++) {
            boolean matches = true;
            for (int index = 0; index < needle.length; index++) {
                if (archive[offset + index] != needle[index]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                System.arraycopy(replacement, 0, archive, offset, replacement.length);
                replacements++;
                offset += needle.length - 1;
            }
        }
        if (replacements != 2) {
            throw new IOException("expected target name in second ZIP local and central headers");
        }
        Files.write(path, archive);
        return path;
    }

    private static byte[] classFile(boolean validMagic, boolean includeRestart, String internalName) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(validMagic ? 0xCAFEBABE : 0x01020304);
            output.writeShort(0);
            output.writeShort(61);
            output.writeShort(includeRestart ? 8 : 7);
            writeUtf8(output, "java/lang/Object");
            output.writeByte(7);
            output.writeShort(1);
            writeUtf8(output, internalName);
            output.writeByte(7);
            output.writeShort(3);
            writeUtf8(output, "<init>");
            writeUtf8(output, "()V");
            if (includeRestart) writeUtf8(output, "-restarted");
            output.writeShort(0x0021);
            output.writeShort(4);
            output.writeShort(2);
            output.writeShort(0); // interfaces
            output.writeShort(0); // fields
            output.writeShort(0); // methods
            output.writeShort(0); // attributes
        }
        return bytes.toByteArray();
    }

    private static byte[] bytesWithMagicAndMarker() {
        byte[] marker = bytes("-restarted");
        byte[] fake = new byte[4 + marker.length];
        fake[0] = (byte) 0xCA;
        fake[1] = (byte) 0xFE;
        fake[2] = (byte) 0xBA;
        fake[3] = (byte) 0xBE;
        System.arraycopy(marker, 0, fake, 4, marker.length);
        return fake;
    }

    private static void writeUtf8(DataOutputStream output, String value) throws IOException {
        output.writeByte(1);
        output.writeUTF(value);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}

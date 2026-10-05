package hu.finominfo.pgrep;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.*;
import static org.junit.Assert.*;

public class PGrepTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Path write(Path file, String text) throws IOException {
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private int run(Path input, Path patterns, Path output, String... extra) {
        List<String> args = new ArrayList<>(Arrays.asList("--input", input.toString(), "--patterns", patterns.toString(), "--output", output.toString()));
        args.addAll(Arrays.asList(extra));
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(log)) {
            return PGrep.run(args.toArray(new String[0]), stream, stream);
        }
    }

    private String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    @Test public void firstAndLastLinesAndLineEndingsArePreserved() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path input = Files.createDirectory(root.resolve("input"));
        write(input.resolve("sample.txt"), "MATCH first\r\nMATCH second\rMATCH third\nMATCH last");
        Path patterns = write(root.resolve("ids"), "MATCH\n");
        Path output = root.resolve("result");
        assertEquals(0, run(input, patterns, output));
        assertEquals("source\tline\texpression\ttext\n"
                + "sample.txt\t1\tMATCH\tMATCH first\n"
                + "sample.txt\t2\tMATCH\tMATCH second\n"
                + "sample.txt\t3\tMATCH\tMATCH third\n"
                + "sample.txt\t4\tMATCH\tMATCH last\n", read(output));
    }

    @Test public void emptyDirectoryAndNoMatchesComplete() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path input = Files.createDirectory(root.resolve("input"));
        Path patterns = write(root.resolve("ids"), "MATCH");
        Path output = root.resolve("result");
        assertEquals(0, run(input, patterns, output));
        assertEquals("source\tline\texpression\ttext\n", read(output));
        write(input.resolve("empty"), "");
        write(input.resolve("other"), "nothing here");
        assertEquals(0, run(input, patterns, output));
        assertEquals("source\tline\texpression\ttext\n", read(output));
    }

    @Test public void rulesAreLiteralDeduplicatedAndRespectFilters() {
        Ids ids = new Ids(Arrays.asList("\uFEFFMATCH", "  ", "MATCH", "***customer", "***account", "---ignore"));
        assertEquals(Collections.singletonList("MATCH"), ids.matches("MATCH customer MATCH"));
        assertEquals(Collections.singletonList("MATCH"), ids.matches("MATCH account"));
        assertTrue(ids.matches("MATCH").isEmpty());
        assertTrue(ids.matches("MATCH customer ignore").isEmpty());
        assertTrue(ids.matches("match customer").isEmpty());
        assertTrue(new Ids(Arrays.asList("a.b")).matches("axb").isEmpty());
        assertEquals(Collections.singletonList(" a "), new Ids(Arrays.asList("\" a \"")).matches(" a "));
    }

    @Test public void emptyExpressionsAreRejected() {
        for (List<String> rules : Arrays.asList(Arrays.asList("  "), Arrays.asList("\"\""), Arrays.asList("x", "***"), Arrays.asList("x", "---"))) {
            try { new Ids(rules); fail("Expected invalid rules to be rejected"); }
            catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().isEmpty()); }
        }
    }

    private void zip(Path path, String entry, String text) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            zip.putNextEntry(new ZipEntry("logs/")); zip.closeEntry();
            zip.putNextEntry(new ZipEntry(entry));
            zip.write(text.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
    }

    private void gzip(Path path, String text) throws IOException {
        try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(path))) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test public void archivesKeepSourceNamesAndSupportGzipAndUppercaseZip() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path input = Files.createDirectory(root.resolve("input"));
        zip(input.resolve("one.zip"), "logs/app.log", "MATCH one");
        zip(input.resolve("two.ZIP"), "logs/app.log", "MATCH two");
        gzip(input.resolve("three.gz"), "MATCH three");
        gzip(input.resolve("legacy.zip"), "MATCH legacy");
        Path output = root.resolve("result");
        assertEquals(0, run(input, write(root.resolve("ids"), "MATCH"), output));
        Set<String> lines = new HashSet<>(Files.readAllLines(output, StandardCharsets.UTF_8));
        assertEquals(5, lines.size());
        assertTrue(lines.contains("one.zip!logs/app.log\t1\tMATCH\tMATCH one"));
        assertTrue(lines.contains("two.ZIP!logs/app.log\t1\tMATCH\tMATCH two"));
        assertTrue(lines.contains("three.gz\t1\tMATCH\tMATCH three"));
        assertTrue(lines.contains("legacy.zip\t1\tMATCH\tMATCH legacy"));
    }

    @Test public void parallelSearchMatchesSerialResultsWithoutDuplicates() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path input = Files.createDirectory(root.resolve("input"));
        for (int i = 0; i < 120; i++) write(input.resolve(i + ".txt"), "MATCH " + i + "\nother\nMATCH final");
        Path patterns = write(root.resolve("ids"), "MATCH");
        Path output = root.resolve("result");
        Path settings = write(root.resolve("settings"), "max-threads=1\nmax-files=1\n");
        assertEquals(0, run(input, patterns, output, "--config", settings.toString()));
        List<String> serial = Files.readAllLines(output, StandardCharsets.UTF_8);
        write(settings, "max-threads=8\nmax-files=3\n");
        assertEquals(0, run(input, patterns, output, "--config", settings.toString()));
        List<String> parallel = Files.readAllLines(output, StandardCharsets.UTF_8);
        assertEquals(241, parallel.size());
        assertEquals(new HashSet<>(serial), new HashSet<>(parallel));
        try (Stream<Path> paths = Files.list(root)) {
            assertFalse(paths.anyMatch(p -> p.getFileName().toString().startsWith(".pgrep-")));
        }
    }

    @Test public void invalidArchivePreservesPreviousOutputAndCleansTemporaryFiles() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path input = Files.createDirectory(root.resolve("input"));
        write(input.resolve("broken.zip"), "not a ZIP file");
        Path output = write(root.resolve("result"), "previous result");
        assertEquals(1, run(input, write(root.resolve("ids"), "MATCH"), output));
        assertEquals("previous result", read(output));
        try (Stream<Path> paths = Files.list(root)) {
            assertFalse(paths.anyMatch(p -> p.getFileName().toString().startsWith(".pgrep-")));
        }
    }

    @Test public void longLinesFailWithoutReplacingOutput() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path input = Files.createDirectory(root.resolve("input"));
        write(input.resolve("large.txt"), "MATCH too long");
        Path output = write(root.resolve("result"), "previous result");
        Path settings = write(root.resolve("settings"), "max-line-chars=5\n");
        assertEquals(1, run(input, write(root.resolve("ids"), "MATCH"), output, "--config", settings.toString()));
        assertEquals("previous result", read(output));
    }

    @Test public void unsafeOutputIsRejected() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path input = Files.createDirectory(root.resolve("input"));
        Path patterns = write(root.resolve("ids"), "MATCH");
        assertEquals(1, run(input, patterns, patterns));
        assertEquals("MATCH", read(patterns));
        assertEquals(1, run(input, patterns, input.resolve("result")));
    }

    @Test public void invalidSettingsFailClearly() throws Exception {
        Path settings = temporary.newFile().toPath();
        for (String value : Arrays.asList("max-threads=0", "max-files=-1", "max-size=wrong", "max-size=1", "unknown=1")) {
            write(settings, value);
            try { new PropertyReader(settings, true); fail("Expected invalid settings to fail"); }
            catch (IllegalArgumentException expected) { assertFalse(expected.getMessage().isEmpty()); }
        }
    }

    @Test public void unicodeAndEscapingArePreserved() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path input = Files.createDirectory(root.resolve("input"));
        write(input.resolve("text.txt"), "árvíztűrő\tC:\\logs");
        Path output = root.resolve("result");
        assertEquals(0, run(input, write(root.resolve("ids"), "árvíz"), output));
        assertTrue(read(output).contains("árvíztűrő\\tC:\\\\logs"));
    }

    @Test public void helpWorksWithoutInputFiles() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(bytes)) {
            assertEquals(0, PGrep.run(new String[]{"--help"}, stream, stream));
            assertTrue(bytes.toString().contains("Usage:"));
            assertEquals(1, PGrep.run(new String[]{"--invalid", "x"}, stream, stream));
        }
    }

    @Test public void crlfAcrossReaderBufferBoundaryIsOneLineBreak() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path input = Files.createDirectory(root.resolve("input"));
        char[] prefix = new char[8191]; Arrays.fill(prefix, 'x');
        write(input.resolve("text.txt"), new String(prefix) + "\r\nMATCH");
        Path output = root.resolve("result");
        assertEquals(0, run(input, write(root.resolve("ids"), "MATCH"), output));
        assertTrue(read(output).contains("text.txt\t2\tMATCH\tMATCH\n"));
    }

    @Test public void invalidUtf8PreservesOutput() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path input = Files.createDirectory(root.resolve("input"));
        Files.write(input.resolve("binary.txt"), new byte[]{(byte) 0xc3, 0x28});
        Path output = write(root.resolve("result"), "previous");
        assertEquals(1, run(input, write(root.resolve("ids"), "MATCH"), output));
        assertEquals("previous", read(output));
    }

    @Test public void corruptZipChecksumFails() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path input = Files.createDirectory(root.resolve("input"));
        byte[] data = "MATCH original".getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32(); crc.update(data);
        Path archive = input.resolve("bad.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            ZipEntry entry = new ZipEntry("entry.txt");
            entry.setMethod(ZipEntry.STORED); entry.setSize(data.length); entry.setCrc(crc.getValue());
            zip.putNextEntry(entry); zip.write(data); zip.closeEntry();
        }
        byte[] bytes = Files.readAllBytes(archive);
        for (int i = 0; i <= bytes.length - data.length; i++) {
            if (Arrays.equals(data, Arrays.copyOfRange(bytes, i, i + data.length))) { bytes[i] = 'X'; break; }
        }
        Files.write(archive, bytes);
        Path output = write(root.resolve("result"), "previous");
        assertEquals(1, run(input, write(root.resolve("ids"), "MATCH"), output));
        assertEquals("previous", read(output));
    }
}

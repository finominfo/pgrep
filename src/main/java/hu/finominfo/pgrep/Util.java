package hu.finominfo.pgrep;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;

/** Streaming archive and text readers. Archive entries are never extracted to disk. */
final class Util {
    private Util() { }

    static void search(Path file, Path root, Ids ids, int maxLineChars, BufferedWriter output) throws IOException {
        String source = root.relativize(file).toString().replace('\\', '/');
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try (BufferedInputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            input.mark(4);
            int a = input.read(), b = input.read(), c = input.read(), d = input.read();
            input.reset();
            if ((a == 0x1f && b == 0x8b) || name.endsWith(".gz") || name.endsWith(".gzip")) {
                try (GZIPInputStream gzip = new GZIPInputStream(input)) { scan(gzip, source, ids, maxLineChars, output); }
            } else if (name.endsWith(".zip") || (a == 'P' && b == 'K'
                    && ((c == 3 && d == 4) || (c == 5 && d == 6) || (c == 7 && d == 8)))) {
                try (ZipFile zip = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        if (!entry.isDirectory()) {
                            try (CheckedInputStream contents = new CheckedInputStream(zip.getInputStream(entry), new CRC32())) {
                                scan(contents, source + "!" + entry.getName(), ids, maxLineChars, output);
                                if (entry.getCrc() != contents.getChecksum().getValue()) {
                                    throw new IOException("CRC mismatch in ZIP entry " + entry.getName());
                                }
                            }
                        }
                    }
                }
            } else { scan(input, source, ids, maxLineChars, output); }
        } catch (IOException e) {
            throw new IOException("Cannot search " + source + ": " + e.getMessage(), e);
        }
    }

    private static void scan(InputStream input, String source, Ids ids, int limit, BufferedWriter output) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()));
        StringBuilder line = new StringBuilder();
        char[] buffer = new char[8192];
        long number = 1;
        boolean afterCR = false;
        int count;
        while ((count = reader.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Search cancelled");
            for (int i = 0; i < count; i++) {
                char value = buffer[i];
                if (value == '\n' && afterCR) { afterCR = false; continue; }
                afterCR = false;
                if (value == '\r' || value == '\n') {
                    writeMatches(source, number++, line.toString(), ids, output);
                    line.setLength(0);
                    afterCR = value == '\r';
                } else {
                    if (line.length() == limit) throw new IOException("Line " + number + " exceeds max-line-chars=" + limit);
                    line.append(value);
                }
            }
        }
        if (line.length() > 0) writeMatches(source, number, line.toString(), ids, output);
    }

    private static void writeMatches(String source, long number, String line, Ids ids, BufferedWriter output) throws IOException {
        for (String expression : ids.matches(line)) {
            output.write(escape(source));
            output.write('\t');
            output.write(Long.toString(number));
            output.write('\t');
            output.write(escape(expression));
            output.write('\t');
            output.write(escape(line));
            output.write('\n');
        }
    }

    static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n");
    }
}

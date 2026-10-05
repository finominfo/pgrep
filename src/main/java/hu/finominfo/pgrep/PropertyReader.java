package hu.finominfo.pgrep;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Validated settings. Missing default configuration uses documented defaults. */
public final class PropertyReader {
    final int maxThreads;
    final int maxFiles;
    final int maxLineChars;

    public PropertyReader(Path path, boolean required) throws IOException {
        Properties properties = new Properties();
        if (required || Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { properties.load(reader); }
        }
        for (String name : properties.stringPropertyNames()) {
            if (!name.equals("max-threads") && !name.equals("max-files")
                    && !name.equals("max-size") && !name.equals("max-line-chars")) {
                throw new IllegalArgumentException("Unknown property: " + name);
            }
        }
        maxThreads = (int) positive(properties, "max-threads", 4, 1024);
        maxFiles = (int) positive(properties, "max-files", 30, 100000);
        long budget = positive(properties, "max-size", 200000000, Long.MAX_VALUE);
        // Conservative allowance for UTF-16 line buffers, strings and escaping.
        long lineBudget = budget / (16L * Math.min(maxThreads, maxFiles));
        if (lineBudget < 1) throw new IllegalArgumentException("max-size is too small for the worker count");
        maxLineChars = (int) positive(properties, "max-line-chars", Math.min(1048576, lineBudget), Integer.MAX_VALUE - 8);
        if (maxLineChars > lineBudget) throw new IllegalArgumentException("max-line-chars exceeds the per-worker max-size budget");
    }

    private static long positive(Properties properties, String key, long fallback, long maximum) {
        String value = properties.getProperty(key, Long.toString(fallback)).trim();
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0 && parsed <= maximum) return parsed;
        } catch (NumberFormatException ignored) {
            // Report the property name and invalid value below.
        }
        throw new IllegalArgumentException(key + " must be between 1 and " + maximum + ": " + value);
    }
}

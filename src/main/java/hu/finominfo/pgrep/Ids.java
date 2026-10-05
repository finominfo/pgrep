package hu.finominfo.pgrep;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Immutable, case-sensitive literal search rules shared by worker threads. */
public final class Ids {
    private final List<String> include;
    private final List<String> require;
    private final List<String> exclude;

    public Ids(Path path) throws IOException {
        this(Files.readAllLines(path, StandardCharsets.UTF_8));
    }

    public Ids(List<String> lines) {
        Set<String> includes = new LinkedHashSet<>();
        Set<String> required = new LinkedHashSet<>();
        Set<String> excluded = new LinkedHashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            String value = lines.get(i);
            if (i == 0 && value.startsWith("\uFEFF")) value = value.substring(1);
            value = value.trim();
            if (value.isEmpty()) continue;
            Set<String> target = includes;
            if (value.startsWith("***") || value.startsWith("---")) {
                target = value.startsWith("***") ? required : excluded;
                value = value.substring(3).trim();
            }
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            if (value.isEmpty()) throw new IllegalArgumentException("Empty search expression at line " + (i + 1));
            target.add(value);
        }
        if (includes.isEmpty()) throw new IllegalArgumentException("At least one search expression is required");
        include = immutable(includes);
        require = immutable(required);
        exclude = immutable(excluded);
    }

    private static List<String> immutable(Set<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    public List<String> matches(String line) {
        for (String pattern : exclude) if (line.contains(pattern)) return Collections.emptyList();
        if (!require.isEmpty()) {
            boolean accepted = false;
            for (String pattern : require) if (line.contains(pattern)) { accepted = true; break; }
            if (!accepted) return Collections.emptyList();
        }
        List<String> matches = new ArrayList<>();
        for (String pattern : include) if (line.contains(pattern)) matches.add(pattern);
        return matches;
    }
}

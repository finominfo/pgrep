package hu.finominfo.pgrep;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 *
 * @author kalman.kovacs@gmail.com
 */
public class Ids {

    private final Set<String> ids;
    private final Set<String> extraIds;
    private final Set<String> minusIds;

    public Ids(String fileName) throws IOException {
        this.ids = new HashSet<>();
        this.extraIds = new HashSet<>();
        this.minusIds = new HashSet<>();
        List<String> myIds = Files.lines(Paths.get(fileName), Charset.forName("UTF-8")).filter(line -> !line.isEmpty()).map(String::trim)
                .map(ln -> ln.startsWith("\"") && ln.endsWith("\"") ? ln.substring(1, ln.length() - 1) : ln).collect(Collectors.toList());
        myIds.forEach((id) -> {
            if (id.startsWith("***")) {
                extraIds.add(id.substring(3));
            } else if (id.startsWith("---")) {
                minusIds.add(id.substring(3));
            } else {
                ids.add(id);
            }
        });
        if (ids.isEmpty()) {
            throw new RuntimeException("There is no any text to find.");
        }
        if (myIds.size() != ids.size() + extraIds.size()) {
            System.out.println("WARNING: THERE ARE REPEATS AMONG THE IDS.");
        }
        System.out.println("To be find:");
        ids.forEach(System.out::println);
        if (!extraIds.isEmpty()) {
            System.out.println("To be find2:");
            extraIds.forEach(System.out::println);
        }
        if (!minusIds.isEmpty()) {
            System.out.println("Not to be find:");
            minusIds.forEach(System.out::println);
        }
    }

    public Set<String> getExtraIds() {
        return extraIds;
    }

    public Set<String> getMinusIds() {
        return minusIds;
    }

    public boolean containsExtraIds(String line) {
        return extraIds.stream().anyMatch(line::contains);
    }

    public boolean containsMinusIds(String line) {
        return minusIds.stream().anyMatch(line::contains);
    }

    public Ids(List<String> ids) {
        this.ids = new HashSet<>();
        this.extraIds = new HashSet<>();
        this.minusIds = new HashSet<>();
        this.ids.addAll(ids);
    }

    public Map<String, Map<String, List<String>>> find(String name, String text) {
        Map<String, Map<String, List<String>>> result = new HashMap<>();
        result.put(name, new HashMap<>());
        for (int i = 0; i < text.length(); i++) {
            for (String id : ids) {
                for (int found = 0; found < id.length() && found + i < text.length(); found++) {
                    if (id.charAt(found) != text.charAt(i + found)) {
                        break;
                    } else if ((found + 1) == id.length()) {
                        copyRow(i, text, result.get(name), id);
                        break;
                    }
                }
            }
        }
        return result;
    }

    public Map<String, Map<String, List<String>>> find2(String name, String text) {
        Map<String, Map<String, List<String>>> result = new HashMap<>();
        Map<String, List<String>> inside = new HashMap<>();
        result.put(name, inside);
        ids.forEach((id) -> {
            int i = 0;
            while ((i = text.indexOf(id, i)) != -1) {
                i = copyRow(i, text, inside, id);
            }
        });
        return result;
    }

    private int copyRow(int i, String text, Map<String, List<String>> result, String id) {
        int rowStart = i;
        int rowEnd = i;
        while (rowStart > 0 && text.charAt(rowStart) != '\n') {
            rowStart--;
        }
        while (rowEnd < text.length() && text.charAt(rowEnd) != '\n') {
            rowEnd++;
        }
        rowStart++;
        StringBuilder copiedLine = new StringBuilder();
        while (rowStart < rowEnd) {
            copiedLine.append(text.charAt(rowStart));
            rowStart++;
        }
        if (!containsMinusIds(copiedLine.toString())) {
            List<String> lines = result.get(id);
            if (lines == null) {
                lines = new ArrayList<>();
                result.put(id, lines);
            }
            lines.add(copiedLine.toString());
        }
        return rowEnd;
    }

}

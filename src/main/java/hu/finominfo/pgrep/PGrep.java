package hu.finominfo.pgrep;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Parallel literal search with bounded in-flight work and streamed results. */
public final class PGrep {
    private PGrep() { }

    public static void main(String[] args) {
        int code = run(args, System.out, System.err);
        if (code != 0) System.exit(code);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        Path input = Paths.get("zip"), patterns = Paths.get("ids.txt");
        Path output = Paths.get("result.txt"), config = Paths.get("pgrep.properties");
        boolean explicitConfig = false;
        try {
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("--help") || args[i].equals("-h")) {
                    out.println("Usage: java -jar pgrep-2.0.0.jar [--input DIR] [--patterns FILE] [--output FILE] [--config FILE]");
                    out.println("Defaults: ./zip, ./ids.txt, ./result.txt, optional ./pgrep.properties");
                    out.println("UTF-8 literal search. Output is replaced only after a successful run.");
                    return 0;
                }
                String option = args[i];
                if (++i == args.length) throw new IllegalArgumentException("Missing value for " + option);
                switch (option) {
                    case "--input": input = Paths.get(args[i]); break;
                    case "--patterns": patterns = Paths.get(args[i]); break;
                    case "--output": output = Paths.get(args[i]); break;
                    case "--config": config = Paths.get(args[i]); explicitConfig = true; break;
                    default: throw new IllegalArgumentException("Unknown option: " + option);
                }
            }
            PropertyReader settings = new PropertyReader(config, explicitConfig);
            Ids ids = new Ids(patterns);
            input = input.toRealPath();
            if (!Files.isDirectory(input)) throw new IllegalArgumentException("Input must be a directory");
            output = output.toAbsolutePath().normalize();
            Path parent = output.getParent().toRealPath();
            output = parent.resolve(output.getFileName());
            if (output.startsWith(input) || Files.isSymbolicLink(output)
                    || output.equals(patterns.toRealPath())
                    || (Files.exists(config) && output.equals(config.toRealPath()))
                    || (Files.exists(output) && (Files.isSameFile(output, patterns)
                    || (Files.exists(config) && Files.isSameFile(output, config))))) {
                throw new IllegalArgumentException("Output must be outside the input directory and must not overwrite configuration or patterns");
            }
            long start = System.nanoTime();
            int searched = search(input, output, ids, settings);
            out.printf("Searched %d files in %.3f seconds. Results: %s%n", searched,
                    (System.nanoTime() - start) / 1_000_000_000.0, output);
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("Search interrupted");
            return 1;
        } catch (IOException | RuntimeException e) {
            err.println("pgrep: " + e.getMessage());
            return 1;
        }
    }

    private static int search(Path root, Path output, Ids ids, PropertyReader settings) throws IOException, InterruptedException {
        Path temporary = Files.createTempDirectory(output.getParent(), ".pgrep-");
        Path combined = temporary.resolve("result.tsv");
        ExecutorService workers = Executors.newFixedThreadPool(Math.min(settings.maxThreads, settings.maxFiles));
        CompletionService<Path> completed = new ExecutorCompletionService<>(workers);
        int pending = 0;
        int searched = 0;
        try {
            try (Stream<Path> paths = Files.walk(root); OutputStream sink = Files.newOutputStream(combined)) {
                sink.write("source\tline\texpression\ttext\n".getBytes(StandardCharsets.UTF_8));
                Iterator<Path> files = paths.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).iterator();
                while (files.hasNext()) {
                    if (pending == settings.maxFiles) { merge(completed, sink); pending--; }
                    Path file = files.next();
                    completed.submit(() -> {
                        Path chunk = Files.createTempFile(temporary, "matches-", ".tsv");
                        try (BufferedWriter writer = Files.newBufferedWriter(chunk, StandardCharsets.UTF_8, StandardOpenOption.WRITE)) {
                            Util.search(file, root, ids, settings.maxLineChars, writer);
                        }
                        return chunk;
                    });
                    pending++;
                    searched++;
                }
                while (pending-- > 0) merge(completed, sink);
            }
            try { Files.move(combined, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(combined, output, StandardCopyOption.REPLACE_EXISTING); }
            return searched;
        } finally {
            workers.shutdownNow();
            boolean interrupted = false;
            while (!workers.isTerminated()) {
                try { workers.awaitTermination(1, TimeUnit.SECONDS); }
                catch (InterruptedException e) { interrupted = true; }
            }
            try (Stream<Path> paths = Files.list(temporary)) {
                for (Iterator<Path> it = paths.iterator(); it.hasNext();) Files.deleteIfExists(it.next());
            } finally {
                Files.deleteIfExists(temporary);
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
    }

    private static void merge(CompletionService<Path> completed, OutputStream sink) throws IOException, InterruptedException {
        try {
            Path chunk = completed.take().get();
            Files.copy(chunk, sink);
            Files.delete(chunk);
        } catch (ExecutionException e) {
            throw new IOException("Search failed: " + e.getCause().getMessage(), e.getCause());
        }
    }
}

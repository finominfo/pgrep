package hu.finominfo.pgrep;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 *
 * @author kalman.kovacs@gmail.com
 */
public class PGrep {

    private static final String IDS_FILE = "./ids.txt";
    private static final String DIRECTORY = "./zip";
    private final int maxThreads;
    private final long maxReadingSize;
    private final int maxFiles;

    private static final String LS = System.lineSeparator();

    private final Ids ids;

    private final AtomicInteger unzippingThreads = new AtomicInteger(0);
    private final AtomicInteger greppingThreads = new AtomicInteger(0);
    private final AtomicBoolean fileReadingInProgress = new AtomicBoolean(false);

    private final List<String> fileNames;
    private final ConcurrentLinkedQueue<byte[]> zippedFiles = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Map<String, byte[]>> unzippedFiles = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Map<String, Map<String, List<String>>>> result = new ConcurrentLinkedQueue<>(); //filename, id, found lines
    private final TreeMap<String, TreeMap<String, List<String>>> orderedResult = new TreeMap<>();// id, filename, foundlines
    private final AtomicBoolean resultOrderingAndWritting = new AtomicBoolean(false);

    private final ScheduledExecutorService executor;

    private volatile int emptyCycles = 0;
    private volatile long cycleCounter = 0;
    private final int allNumOfFiles;

    private final TimeHandler timeHandler;

    public PGrep() throws IOException {
        timeHandler = new TimeHandler();
        ids = new Ids(IDS_FILE);
        fileNames = Files.walk(Paths.get(DIRECTORY)).filter(Files::isRegularFile).map(Object::toString).sorted().collect(Collectors.toList());
        System.out.println("Number of files: " + fileNames.size());
        allNumOfFiles = fileNames.size();
        PropertyReader propertyReader = new PropertyReader();
        maxThreads = propertyReader.getMaxThreads();
        maxReadingSize = propertyReader.getMaxReadingSize();
        maxFiles = propertyReader.getMaxFiles();
        executor = new ScheduledThreadPoolExecutor(maxThreads << 1);
    }

    private void read() {
        if (fileReadingInProgress.compareAndSet(false, true)) {
            try {
                long shouldRead = maxReadingSize - getSizeOfAllReadFiles();
                long thisCycleRead = 0;
                while (!fileNames.isEmpty() && thisCycleRead < shouldRead && zippedFiles.size() + unzippedFiles.size() < maxFiles) {
                    try {
                        String fileName = fileNames.remove(0);
                        if (fileName.endsWith("zip")) {
                            byte[] readAllBytes = Files.readAllBytes(Paths.get(fileName));
                            zippedFiles.add(readAllBytes);
                            thisCycleRead += readAllBytes.length;
                        } else {
                            Map<String, byte[]> map = new HashMap<>();
                            byte[] readAllBytes = Files.readAllBytes(Paths.get(fileName));
                            map.put(fileName, readAllBytes);
                            unzippedFiles.add(map);
                            thisCycleRead += readAllBytes.length;
                        }
                    } catch (IOException ex) {
                        Logger.getLogger(PGrep.class.getName()).log(Level.SEVERE, null, ex);
                    }
                }
            } finally {
                fileReadingInProgress.set(false);
            }
        }
    }

    private long getSizeOfAllReadFiles() {
        return getAllZippedSize() + getAllUnzippedSize();
    }

    private long getAllZippedSize() {
        return zippedFiles.stream().mapToLong(bytes -> bytes.length).sum();
    }

    private long getAllUnzippedSize() {
        return unzippedFiles.stream().map(map -> map.values().iterator().next()).mapToLong(bytes -> bytes.length).sum();
    }

    private void cycle() {
        if (unzippingThreads.get() + greppingThreads.get() < maxThreads) {
            if (zippedFiles.size() > unzippedFiles.size()) {
                executor.submit(this::unzip);
            } else if (!unzippedFiles.isEmpty()) {
                executor.submit(this::grep);
            }
        }
        if (fileNames.isEmpty() && unzippedFiles.isEmpty() && zippedFiles.isEmpty()
                && unzippingThreads.get() == 0 && greppingThreads.get() == 0 && ((++emptyCycles) > 15)) {
            showMetrics();
            System.out.println("Order and write result...");
            orderAndWriteResult();
            executor.shutdown();
        } else {
            if (((++cycleCounter) & 0x7f) == 0) {
                showMetrics();
            }
            executor.schedule(this::cycle, 100L, TimeUnit.MILLISECONDS);
        }
    }

    private void unzip() {
        unzippingThreads.incrementAndGet();
        try {
            byte[] bytes;
            while (unzippedFiles.size() < 8 && (bytes = zippedFiles.poll()) != null) {
                Util.unzipInside(bytes, unzippedFiles);
            }
            read();
        } finally {
            unzippingThreads.decrementAndGet();
        }
        if (unzippedFiles.size() > 0 && unzippingThreads.get() + greppingThreads.get() < maxThreads) {
            executor.submit(this::grep);
        } else {
            if (zippedFiles.size() > 0 && unzippingThreads.get() + greppingThreads.get() < maxThreads) {
                executor.submit(this::unzip);
            } else {
                if (unzippingThreads.get() + greppingThreads.get() < maxThreads && !fileNames.isEmpty()) {
                    System.out.print(" WAITING...");
                }
            }
        }
    }

    private void grep() {
        greppingThreads.incrementAndGet();
        try {
            Map<String, byte[]> map;
            while ((map = unzippedFiles.poll()) != null) {
                Map.Entry<String, byte[]> first = map.entrySet().iterator().next();
                Map<String, Map<String, List<String>>> find2 = ids.find2(first.getKey(), new String(first.getValue(), Charset.forName("UTF-8")));
                result.add(find2);
            }
            if (result.size() > 500) {
                orderAndWriteResult();
            }
            read();
        } finally {
            greppingThreads.decrementAndGet();
        }
        if (zippedFiles.size() > 0 && unzippingThreads.get() + greppingThreads.get() < maxThreads) {
            executor.submit(this::unzip);
        } else {
            if (unzippedFiles.size() > 0 && unzippingThreads.get() + greppingThreads.get() < maxThreads) {
                executor.submit(this::grep);
            } else {
                if (unzippingThreads.get() + greppingThreads.get() < maxThreads && !fileNames.isEmpty()) {
                    System.out.print("WAITING...");
                }
            }
        }
    }

    private void showMetrics() {
        int remaining = fileNames.size() + zippedFiles.size() + unzippedFiles.size() + unzippingThreads.get() + greppingThreads.get();
        System.out.print("files: " + fileNames.size() + " - zips: " + zippedFiles.size() + " - texts: " + unzippedFiles.size());
        System.out.print(" - unzips: " + unzippingThreads.get() + " - greps: " + greppingThreads.get());
        System.out.println(" - elapsed time: " + timeHandler + " - remaining time: " + timeHandler.getRemainingTime(remaining, allNumOfFiles));
    }

    private void orderAndWriteResult() {
        if (resultOrderingAndWritting.compareAndSet(false, true)) {
            try {

                Map<String, Map<String, List<String>>> map;
                while ((map = result.poll()) != null) {
                    map.entrySet().stream().forEach(entry -> {
                        String fileName = entry.getKey();
                        Map<String, List<String>> foundLines = entry.getValue();
                        foundLines.entrySet().forEach(idLines -> {
                            TreeMap<String, List<String>> details = orderedResult.get(idLines.getKey());
                            if (details == null) {
                                details = new TreeMap<>();
                                orderedResult.put(idLines.getKey(), details);
                            }
                            List<String> lines = details.get(fileName);
                            if (lines == null) {
                                lines = new ArrayList<>();
                                details.put(fileName, lines);
                            }
                            lines.addAll(idLines.getValue());
                        });
                    });
                }
                StringBuilder sb = new StringBuilder();
                orderedResult.entrySet().stream().forEach(e1 -> {
                    sb.append(LS).append(LS).append("***** EXPRESSION: ").append(e1.getKey()).append(LS);
                    e1.getValue().entrySet().stream().forEach(e2 -> {
                        sb.append(LS).append("[filename: ").append(e2.getKey()).append("]").append(LS).append(LS);
                        e2.getValue().forEach(line -> sb.append(line).append(LS));
                    });
                });
                try {
                    Files.write(Paths.get("./result.txt"), sb.toString().getBytes(), CREATE, APPEND);
                } catch (IOException ex) {
                    Logger.getLogger(PGrep.class.getName()).log(Level.SEVERE, null, ex);
                }
                orderedResult.clear();
            } finally {
                resultOrderingAndWritting.set(false);
            }
        }
    }

    public static void main(String[] args) throws IOException {
        PGrep pGrep = new PGrep();
        pGrep.executor.submit(pGrep::read);
        pGrep.executor.submit(pGrep::cycle);
        System.out.println("Start working...");
    }
}

package hu.finominfo.pgrep;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 *
 * @author kalman.kovacs
 */
public class Util {

    public static boolean isGZipped(InputStream in) {
        if (!in.markSupported()) {
            in = new BufferedInputStream(in);
        }
        in.mark(2);
        int magic = 0;
        try {
            magic = in.read() & 0xff | ((in.read() << 8) & 0xff00);
            in.reset();
        } catch (IOException e) {
            e.printStackTrace(System.err);
            return false;
        }
        return magic == GZIPInputStream.GZIP_MAGIC;
    }

    public static void unzipInside(byte[] bytes, ConcurrentLinkedQueue<Map<String, byte[]>> unzippedFiles) {
        try {
            try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes)) {
                if (Util.isGZipped(byteArrayInputStream)) {
                    try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
                        byte[] buffer = new byte[0x1000000];
                        try (GZIPInputStream zipInputStream = new GZIPInputStream(byteArrayInputStream)) {
                            int len;
                            while ((len = zipInputStream.read(buffer)) > 0) {
                                byteArrayOutputStream.write(buffer, 0, len);
                            }
                        }
                        byte[] unzipped = byteArrayOutputStream.toByteArray();
                        Map<String, byte[]> map = new HashMap<>();
                        map.put("Unknown", unzipped);
                        unzippedFiles.add(map);
                    }
                } else {
                    try (ZipInputStream zipInputStream = new ZipInputStream(byteArrayInputStream)) {
                        ZipEntry entry;
                        while ((entry = zipInputStream.getNextEntry()) != null) {
                            if (!entry.isDirectory()) {
                                try (ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
                                    byte[] buffer = new byte[0x1000000];
                                    int len;
                                    while ((len = zipInputStream.read(buffer)) > 0) {
                                        byteArrayOutputStream.write(buffer, 0, len);
                                    }
                                    byte[] unzipped = byteArrayOutputStream.toByteArray();
                                    Map<String, byte[]> map = new HashMap<>();
                                    map.put(entry.getName(), unzipped);
                                    unzippedFiles.add(map);
                                }
                            }
                            zipInputStream.closeEntry();
                        }
                    }
                }
            }
        } catch (IOException ex) {
            Logger.getLogger(PGrep.class.getName()).log(Level.SEVERE, null, ex);
        }
    }

}

package hu.finominfo.pgrep;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 *
 * @author kalman.kovacs
 */
public class PropertyReader {

    private final int maxThreads;
    private final long maxReadingSize;
    private final int maxFiles;

    public PropertyReader() {
        Properties prop = new Properties();
        try {
            prop.load(new FileInputStream("./kgrep.properties"));
        } catch (IOException ex) {
        }
        maxThreads = Integer.valueOf(prop.getProperty("max-threads", "4"));
        maxReadingSize = Long.valueOf(prop.getProperty("max-size", "200000000"));
        maxFiles = Integer.valueOf(prop.getProperty("max-files", "30"));
    }

    public int getMaxThreads() {
        return maxThreads;
    }

    public long getMaxReadingSize() {
        return maxReadingSize;
    }

    public int getMaxFiles() {
        return maxFiles;
    }
}

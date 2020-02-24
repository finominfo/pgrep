package hu.finominfo.pgrep;

import java.util.concurrent.atomic.AtomicLong;

/**
 * @author kalman.kovacs
 */
public class TimeHandler {

    private final long start;
    private final AtomicLong summarizedAllRunningTime = new AtomicLong(0);
    private final AtomicLong callCounter = new AtomicLong(0);

    public TimeHandler() {
        start = System.currentTimeMillis();
    }


    public String getRemainingTime(int remainingPieces, int allPieces) {
        long elapsedTime = System.currentTimeMillis() - start;
        long remainingTime = elapsedTime * remainingPieces / (allPieces - remainingPieces);
        if ((remainingPieces << 3) > allPieces || remainingTime > 300_000L) {
            long averageAll = summarizedAllRunningTime.addAndGet(elapsedTime + remainingTime) / callCounter.incrementAndGet();
            remainingTime = averageAll - elapsedTime;
        }
        return getTime(remainingTime);
    }

    @Override
    public String toString() {
        long elapsedTime = System.currentTimeMillis() - start;
        return getTime(elapsedTime);
    }

    private String getTime(long time) {
        long millis = time % 1000;
        long second = (time / 1000) % 60;
        long minute = (time / (1000 * 60)) % 60;
        long hour = (time / (1000 * 60 * 60)) % 24;
        return String.format("%02d:%02d:%02d.%d", hour, minute, second, millis);
    }

}

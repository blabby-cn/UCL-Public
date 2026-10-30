package net.kdt.pojavlaunch.utils;

/**
 * A single byte budget shared by every connection of a download.
 *
 * <p>The limit applies to the transfer as a whole, not to each socket: four
 * ranged connections with a per-socket cap would deliver four times the number
 * the user asked for. Each copy loop reports what it just wrote, and the budget
 * decides how long that loop has to wait before it may continue.
 *
 * <p>With no limit set this costs one volatile read per buffer, which is what
 * the download did before the setting existed.
 */
public final class UclThrottle {

    private static volatile long sBytesPerSecond;

    private static long sWindowStart = System.nanoTime();
    private static long sWindowBytes;

    private UclThrottle() {
    }

    /** Bytes per second, zero or negative for no limit. */
    public static void setLimit(long bytesPerSecond) {
        sBytesPerSecond = Math.max(0L, bytesPerSecond);
        synchronized (UclThrottle.class) {
            sWindowStart = System.nanoTime();
            sWindowBytes = 0L;
        }
    }

    public static long limit() {
        return sBytesPerSecond;
    }

    /**
     * Records bytes just transferred, and sleeps if the budget is spent.
     *
     * <p>One second of window: long enough that the sleep is a couple of
     * hundred milliseconds at most in normal use, short enough that a stalled
     * connection does not bank credit and then burst.
     */
    public static void consume(int bytes) {
        long limit = sBytesPerSecond;
        if (limit <= 0L || bytes <= 0) return;

        synchronized (UclThrottle.class) {
            sWindowBytes += bytes;
            long elapsed = System.nanoTime() - sWindowStart;
            long allowed = sWindowBytes * 1_000_000_000L / limit;
            long wait = allowed - elapsed;
            if (wait > 0L) {
                try {
                    Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (elapsed > 1_000_000_000L) {
                sWindowStart = System.nanoTime();
                sWindowBytes = 0L;
            }
        }
    }
}

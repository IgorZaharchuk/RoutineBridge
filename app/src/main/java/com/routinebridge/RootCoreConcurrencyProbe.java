package com.routinebridge;

public final class RootCoreConcurrencyProbe {
    private static final Object LOCK = new Object();

    private static int active;
    private static int maximum;

    public RootCoreConcurrencyProbe() {
    }

    public static void reset() {
        synchronized (LOCK) {
            if (active != 0) {
                throw new IllegalStateException(
                    "Cannot reset while calls are active: " + active
                );
            }
            maximum = 0;
        }
    }

    public static int getMaximum() {
        synchronized (LOCK) {
            return maximum;
        }
    }

    public void runConcurrent(int expected, long delayMs) throws Exception {
        if (expected < 1 || expected > 4) {
            throw new IllegalArgumentException(
                "Expected concurrent calls must be between 1 and 4"
            );
        }

        synchronized (LOCK) {
            active++;
            if (active > maximum) {
                maximum = active;
            }
            LOCK.notifyAll();
        }

        try {
            synchronized (LOCK) {
                long deadline = System.currentTimeMillis() + 5_000L;

                while (active < expected) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0L) {
                        throw new IllegalStateException(
                            "Concurrency barrier timed out: expected="
                                + expected + ", active=" + active
                        );
                    }
                    LOCK.wait(remaining);
                }
            }

            Thread.sleep(delayMs);
        } finally {
            synchronized (LOCK) {
                active--;
                LOCK.notifyAll();
            }
        }
    }
}

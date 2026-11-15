package com.yashgamerx.core;

import java.util.concurrent.locks.LockSupport;

/**
 * Sub-millisecond waiting. OS timers (notably on Windows) overshoot by 1-3 ms, so we
 * park only for the bulk of long waits and spin-yield for the final few milliseconds.
 */
public final class Clock {
    private Clock() {}

    public static final long MS = 1_000_000L;

    public static void sleepUntil(long deadlineNanos) {
        for (;;) {
            long rem = deadlineNanos - System.nanoTime();
            if (rem <= 0 || Thread.currentThread().isInterrupted()) return;
            if (rem > 3 * MS) LockSupport.parkNanos(rem - (5 * MS) / 2);
            else Thread.yield();
        }
    }
}

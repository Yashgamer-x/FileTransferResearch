package com.yashgamerx.emu;

import com.yashgamerx.core.Clock;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Executes actions at scheduled nanoTime instants, in time order (so jitter can reorder packets). */
final class DelayLine implements AutoCloseable {
    private record Item(long time, long seq, Runnable action) implements Comparable<Item> {
        public int compareTo(Item o) {
            int c = Long.compare(time, o.time);
            return c != 0 ? c : Long.compare(seq, o.seq);
        }
    }

    private final PriorityBlockingQueue<Item> queue = new PriorityBlockingQueue<>();
    private final AtomicLong seq = new AtomicLong();
    private final Thread thread;
    private volatile boolean closed;

    DelayLine(String name) {
        thread = new Thread(this::loop, name);
        thread.setDaemon(true);
        thread.start();
    }

    void schedule(long time, Runnable action) {
        queue.add(new Item(time, seq.getAndIncrement(), action));
    }

    private void loop() {
        try {
            while (!closed) {
                Item head = queue.poll(50, TimeUnit.MILLISECONDS);
                if (head == null) continue;
                for (;;) {
                    long rem = head.time - System.nanoTime();
                    if (rem <= 0) break;
                    Item p = queue.peek();
                    if (p != null && p.compareTo(head) < 0) { // something earlier arrived meanwhile
                        queue.add(head);
                        head = queue.poll();
                        continue;
                    }
                    Clock.sleepUntil(Math.min(head.time, System.nanoTime() + Clock.MS / 4));
                }
                try {
                    head.action.run();
                } catch (RuntimeException ignored) {
                }
            }
        } catch (InterruptedException ignored) {
        }
    }

    @Override
    public void close() {
        closed = true;
        thread.interrupt();
    }
}

package com.yashgamerx.core;

import java.util.function.Consumer;

public final class Threads {
    private Threads() {}

    @FunctionalInterface
    public interface Body {
        void run() throws Exception;
    }

    public static Thread start(String name, Body body, Consumer<Throwable> onError) {
        Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable e) {
                if (onError != null) onError.accept(e);
            }
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }
}

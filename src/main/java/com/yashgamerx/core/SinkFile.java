package com.yashgamerx.core;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Memory-mapped output file that accepts chunks in any order. Disjoint writes are thread-safe. */
public final class SinkFile implements AutoCloseable {
    private final FileChannel fc;
    private final Arena arena;
    private final MemorySegment seg;
    public final long size;
    public final int chunk;
    public final int chunks;
    private boolean closed;

    public SinkFile(Path path, long size, int chunk) throws IOException {
        if (size <= 0) throw new IOException("invalid file size " + size);
        if (chunk <= 0 || chunk > 65_000) throw new IOException("invalid chunk size " + chunk);
        this.fc = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        this.size = size;
        this.chunk = chunk;
        this.chunks = Chunks.count(size, chunk);
        this.arena = Arena.ofShared();
        this.seg = fc.map(FileChannel.MapMode.READ_WRITE, 0, size, arena);
    }

    public int length(int idx) {
        return Chunks.length(size, chunk, idx);
    }

    public void write(int idx, byte[] src, int off) {
        MemorySegment.copy(src, off, seg, ValueLayout.JAVA_BYTE, (long) idx * chunk, length(idx));
    }

    public int read(int idx, byte[] dst, int off) {
        int len = length(idx);
        MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, (long) idx * chunk, dst, off, len);
        return len;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        arena.close();
        fc.close();
    }
}

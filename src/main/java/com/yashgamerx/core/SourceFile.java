package com.yashgamerx.core;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Memory-mapped, chunk-addressable view of the file being sent (no 2 GiB limit). */
public final class SourceFile implements AutoCloseable {
    private final FileChannel fc;
    private final Arena arena;
    private final MemorySegment seg;
    public final long size;
    public final int chunk;
    public final int chunks;

    public SourceFile(Path path, int chunk) throws IOException {
        this.fc = FileChannel.open(path, StandardOpenOption.READ);
        this.size = fc.size();
        if (size == 0) throw new IOException("empty file");
        this.chunk = chunk;
        this.chunks = Chunks.count(size, chunk);
        this.arena = Arena.ofShared();
        this.seg = fc.map(FileChannel.MapMode.READ_ONLY, 0, size, arena);
    }

    public int length(int idx) {
        return Chunks.length(size, chunk, idx);
    }

    /** Copies chunk {@code idx} into {@code dst[off..]} and returns its length. */
    public int read(int idx, byte[] dst, int off) {
        int len = length(idx);
        MemorySegment.copy(seg, ValueLayout.JAVA_BYTE, (long) idx * chunk, dst, off, len);
        return len;
    }

    @Override
    public void close() throws IOException {
        arena.close();
        fc.close();
    }
}

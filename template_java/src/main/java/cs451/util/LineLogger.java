package cs451.util;

import java.io.*;
import java.util.Set;

/**
 * Buffered logger for broadcast/delivery events.
 */
public final class LineLogger {

    private final BufferedWriter writer;

    public LineLogger(String path) throws IOException {
        this.writer = new BufferedWriter(new FileWriter(path), 8 * 1024 * 1024);
    }

    public synchronized void logB(int seq) {
        try {
            writer.write('b');
            writer.write(' ');
            writer.write(Integer.toString(seq));
            writer.newLine();
        } catch (IOException ignored) {}
    }

    public synchronized void logD(int senderId, int seq) {
        try {
            writer.write('d');
            writer.write(' ');
            writer.write(Integer.toString(senderId));
            writer.write(' ');
            writer.write(Integer.toString(seq));
            writer.newLine();
        } catch (IOException ignored) {}
    }

    /**
     * Log a lattice agreement decision.
     * Format: space-separated values in ascending order on one line.
     */
    public synchronized void logDecision(Set<Integer> values) {
        try {
            // Sort values for deterministic output
            int[] sorted = values.stream().mapToInt(Integer::intValue).sorted().toArray();
            for (int i = 0; i < sorted.length; i++) {
                if (i > 0) {
                    writer.write(' ');
                }
                writer.write(Integer.toString(sorted[i]));
            }
            writer.newLine();
        } catch (IOException ignored) {}
    }

    public void flush() {
        try { writer.flush(); } catch (IOException ignored) {}
    }

    public void close() {
        try { writer.close(); } catch (IOException ignored) {}
    }
}

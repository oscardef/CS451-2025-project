package cs451.util;

import java.io.*;

/** 
 * Simplified, efficient LineLogger.
 * Keeps a single BufferedWriter open and flushes manually.
 */
public final class LineLogger {

    private final BufferedWriter writer;

    public LineLogger(String path) throws IOException {
        this.writer = new BufferedWriter(new FileWriter(path), 8 * 1024 * 1024);
    }

    public void logB(int seq) {
        try {
            writer.write('b');
            writer.write(' ');
            writer.write(Integer.toString(seq));
            writer.newLine();
        } catch (IOException ignored) {}
    }

    public void logD(int senderId, int seq) {
        try {
            writer.write('d');
            writer.write(' ');
            writer.write(Integer.toString(senderId));
            writer.write(' ');
            writer.write(Integer.toString(seq));
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

package cs451.util;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * LineLogger — thread-safe logger for CS451 outputs.
 *
 * Each process writes exactly one output file, containing lines in the form:
 *
 *   b <seq>          → broadcasted message with sequence number <seq>
 *   d <sender> <seq> → delivered message <seq> from process <sender>
 *
 * The autograder will parse these exact formats.
 */
public class LineLogger {

    private final String path;
    private final List<String> lines = new ArrayList<>();

    public LineLogger(String path) {
        this.path = path;
    }

    /** Log a broadcast (send) event. */
    public synchronized void logB(int seq) {
        lines.add("b " + seq);
    }

    /** Log a delivery event. */
    public synchronized void logD(int senderId, int seq) {
        lines.add("d " + senderId + " " + seq);
    }

    /** Flush buffered log lines to disk. */
    public synchronized void flush() {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(path))) {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
            writer.flush();
        } catch (IOException e) {
            System.err.println("[LineLogger] Failed to flush logs: " + e.getMessage());
        }
    }
}

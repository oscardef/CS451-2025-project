package cs451;

import cs451.net.UdpChannel;
import cs451.links.fairloss.FairLossLinkImpl;
import cs451.links.stubborn.StubbornLinkImpl;
import cs451.links.perfect.PerfectLinkImpl;
import cs451.util.LineLogger;

import java.util.List;
import java.util.Scanner;

/**
 * ============================================================================
 *  Main.java — Entry point for Milestone 1: Perfect Links
 * ============================================================================
 *
 *  Responsibilities:
 *   - Parse input arguments and configuration file.
 *   - Initialize network stack:
 *         PerfectLink → StubbornLink → FairLossLink → UDP
 *   - Coordinate sender and receiver logic.
 *   - Handle graceful termination and output flushing.
 *
 *  Design goals:
 *   - Minimal console I/O (to avoid throttling).
 *   - Deterministic and consistent logging behaviour.
 *   - Tuned batching and pacing for high throughput.
 *
 *  Usage pattern:
 *   - Process 1 acts as receiver (only delivers + logs).
 *   - All other processes send `m` messages to process 1.
 * ============================================================================
 */
public final class Main {

    /* --------------------------- Configuration --------------------------- */

    /** Whether to mute console output for max performance. */
    private static final boolean MUTE_CONSOLE = true;

    /** Flush the log file every N deliveries/sends. */
    private static final int LOG_FLUSH_INTERVAL = 1000;

    /** Optional pacing interval to avoid socket overload (in msgs). */
    private static final int SEND_PACING_INTERVAL = 5000;

    /** Sleep time for pacing in milliseconds. */
    private static final int SEND_PACING_DELAY_MS = 1;

    /** Empty payload (messages only carry sequence IDs). */
    private static final byte[] EMPTY_PAYLOAD = new byte[0];


    /* --------------------------- Utility Methods --------------------------- */

    /** Prints to console if MUTE_CONSOLE = false. */
    private static void log(String msg) {
        if (!MUTE_CONSOLE) {
            System.out.println(msg);
        }
    }

    /** Handles clean shutdown on Ctrl+C or system termination. */
    private static void handleSignal(LineLogger logger, PerfectLinkImpl pl) {
        log("Stopping network processing...");
        try {
            pl.stop();
            logger.flush();
        } catch (Exception e) {
            System.err.println("Error during shutdown: " + e.getMessage());
        }
        log("Shutdown complete. Output flushed.");
    }

    /** Registers JVM shutdown hook for graceful termination. */
    private static void initSignalHandlers(LineLogger logger, PerfectLinkImpl pl) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> handleSignal(logger, pl)));
    }


    /* --------------------------- Main Execution --------------------------- */

    public static void main(String[] args) throws Exception {
        /* --- Parse CLI and configuration --- */
        Parser parser = new Parser(args);
        parser.parse();

        int myId = parser.myId();
        List<Host> hosts = parser.hosts();
        Host me = hosts.get(myId - 1);

        // Read <m> (number of messages) and <receiverId> from config
        String configPath = parser.config();
        int messageCount = 0;
        int receiverId = 0;
        try (Scanner sc = new Scanner(new java.io.File(configPath))) {
            if (sc.hasNextInt()) messageCount = sc.nextInt();
            if (sc.hasNextInt()) receiverId = sc.nextInt();
        }

        /* --- Initialize logger and networking stack --- */
        var logger = new LineLogger(parser.output());
        var udp = UdpChannel.bind(me.getPort(), 0);
        var flp = new FairLossLinkImpl(myId, hosts, udp);
        var slp = new StubbornLinkImpl(flp, hosts);
        var pl = new PerfectLinkImpl(myId, hosts, slp);

        initSignalHandlers(logger, pl);

        /* --- Register delivery handler --- */
        final int[] deliveryCount = {0}; // must be effectively final for lambda

        pl.onDeliver((sender, seq, data) -> {
            logger.logD(sender, seq);
            deliveryCount[0]++;

            if (deliveryCount[0] % LOG_FLUSH_INTERVAL == 0) {
                logger.flush();
                if (!MUTE_CONSOLE) {
                    log(String.format("[Deliver] Total: %,d messages", deliveryCount[0]));
                }
            }
        });

        /* --- Start network stack --- */
        pl.start();

        /* --- Sender or receiver role --- */
        if (myId != receiverId) {
            Host receiver = hosts.get(receiverId - 1);
            log(String.format("Sender %d → Receiver %d | Total messages: %,d",
                    myId, receiverId, messageCount));

            for (int seq = 1; seq <= messageCount; seq++) {
                logger.logB(seq);
                pl.send(receiver, EMPTY_PAYLOAD, seq);

                // Periodically flush logs
                if (seq % LOG_FLUSH_INTERVAL == 0) {
                    logger.flush();
                }

                // Lightweight pacing to smooth CPU/network usage
                if (seq % SEND_PACING_INTERVAL == 0) {
                    Thread.sleep(SEND_PACING_DELAY_MS);
                }
            }

            logger.flush(); // ensure all send logs are written
            log(String.format("Sender %d finished sending %,d messages.", myId, messageCount));
        } else {
            log(String.format("Receiver %d waiting for messages...", myId));
        }

        /* --- Passive wait (until terminated externally) --- */
        synchronized (Main.class) {
            Main.class.wait();
        }
    }
}

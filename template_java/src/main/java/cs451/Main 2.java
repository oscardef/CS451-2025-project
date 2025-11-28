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
 *  Graph:
 *      PerfectLink → StubbornLink → FairLossLink → UDP
 *
 *  Mode:
 *      - Process <receiverId> (from config) acts as the receiver and logs all deliveries.
 *      - All other processes send <m> messages to <receiverId>.
 *
 *  Focus:
 *      - High throughput: big UDP buffers, batching in PL, minimal flushing.
 *      - No background flusher threads in PL (counter-based soft flush instead).
 * ============================================================================
 */
public final class Main {

    /* --------------------------- Configuration --------------------------- */

    /** Disable console noise for performance. */
    private static final boolean MUTE_CONSOLE = true;

    /** Flush the log file every N deliveries/sends (count-based, not seq-based). */
    private static final int LOG_FLUSH_INTERVAL = 50_000;

    /** Empty payload (we only care about seq for this milestone). */
    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    /* --------------------------- Utility --------------------------- */

    private static void log(String msg) {
        if (!MUTE_CONSOLE) System.out.println(msg);
    }

    private static void handleSignal(LineLogger logger, PerfectLinkImpl pl) {
        log("Stopping network processing...");
        try {
            pl.stop();   // flushes PL batches
            logger.flush();
        } catch (Exception e) {
            System.err.println("Error during shutdown: " + e.getMessage());
        }
        log("Shutdown complete. Output flushed.");
    }

    private static void initSignalHandlers(LineLogger logger, PerfectLinkImpl pl) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> handleSignal(logger, pl)));
    }

    /* --------------------------- Main --------------------------- */

    public static void main(String[] args) throws Exception {
        // Parse CLI
        Parser parser = new Parser(args);
        parser.parse();

        int myId = parser.myId();
        List<Host> hosts = parser.hosts();
        Host me = hosts.get(myId - 1);

        // Config: "<m> <receiverId>"
        String configPath = parser.config();
        int messageCount = 0;
        int receiverId   = 0;
        try (Scanner sc = new Scanner(new java.io.File(configPath))) {
            if (sc.hasNextInt()) messageCount = sc.nextInt();
            if (sc.hasNextInt()) receiverId   = sc.nextInt();
        }

        // Logger + stack
        var logger = new LineLogger(parser.output());
        var udp    = UdpChannel.bind(me.getPort(), 0);
        var flp    = new FairLossLinkImpl(myId, hosts, udp);
        var slp    = new StubbornLinkImpl(flp, hosts);
        var pl     = new PerfectLinkImpl(myId, hosts, slp);

        initSignalHandlers(logger, pl);

        // Deliveries (count-based flush so it’s regular even if seqs are irregular)
        final int[] deliveredCount = {0};

        pl.onDeliver((sender, seq, data) -> {
            logger.logD(sender, seq);
            if ((++deliveredCount[0] % LOG_FLUSH_INTERVAL) == 0) {
                logger.flush();
                if (!MUTE_CONSOLE) log(String.format("[deliver] total=%,d", deliveredCount[0]));
            }
        });

        pl.start();

        // Sender vs receiver
        if (myId != receiverId) {
            Host receiver = hosts.get(receiverId - 1);
            if (!MUTE_CONSOLE)
                log(String.format("Sender %d → Receiver %d | messages: %,d", myId, receiverId, messageCount));

            // Log every broadcast line (required by spec) but flush sparsely
            for (int seq = 1; seq <= messageCount; seq++) {
                logger.logB(seq);
                pl.send(receiver, EMPTY_PAYLOAD, seq);

                if ((seq % LOG_FLUSH_INTERVAL) == 0) {
                    logger.flush();
                }
            }

            logger.flush(); // end-of-sends
            if (!MUTE_CONSOLE)
                log(String.format("Sender %d finished %,d messages.", myId, messageCount));

        } else {
            if (!MUTE_CONSOLE) log(String.format("Receiver %d waiting for messages...", myId));
        }

        // Idle forever; shutdown hook handles cleanup.
        synchronized (Main.class) {
            Main.class.wait();
        }
    }
}

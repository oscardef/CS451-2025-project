package cs451;

import cs451.net.UdpChannel;
import cs451.links.fairloss.FairLossLinkImpl;
import cs451.links.stubborn.StubbornLinkImpl;
import cs451.links.perfect.PerfectLinkImpl;
import cs451.util.LineLogger;

import java.net.SocketException;
import java.util.List;
import java.util.Scanner;

/**
 * Main entry point for Milestone 1: Perfect Links
 * ---------------------------------------------------------------
 * - Process 1 acts as receiver (logs all deliveries)
 * - All other processes send messages to process 1
 * - Periodic logging and minimal I/O overhead
 * - Terminates after all sends complete
 */
public class Main {

    // Control flags
    private static final boolean MUTE = false;        // disable console output for performance
    private static final int LOG_INTERVAL = 50;     // log every Nth message (adjust for submission)


    private static void log(String msg) {
        if (!MUTE) System.out.println(msg);
    }

    private static void handleSignal(LineLogger logger, PerfectLinkImpl pl) {
        log("Immediately stopping network packet processing.");
        try {
            pl.stop();
            logger.flush();
        } catch (Exception e) {
            System.err.println("Error during shutdown: " + e.getMessage());
        }
        log("Writing output completed.");
    }

    private static void initSignalHandlers(LineLogger logger, PerfectLinkImpl pl) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> handleSignal(logger, pl)));
    }


    public static void main(String[] args) throws Exception {
        Parser parser = new Parser(args);
        parser.parse();

        int myId = parser.myId();
        List<Host> hosts = parser.hosts();
        Host me = hosts.get(myId - 1);

        // ---- Read config: "m i" ----
        String configPath = parser.config();
        int m = 0, receiverId = 0;
        try (Scanner sc = new Scanner(new java.io.File(configPath))) {
            if (sc.hasNextInt()) m = sc.nextInt();
            if (sc.hasNextInt()) receiverId = sc.nextInt();
        }

        // Initialize logger before signal handlers
        var logger = new LineLogger(parser.output());

        // Setup layers
        var channel = UdpChannel.bind(me.getPort(), 0);
        var flp = new FairLossLinkImpl(myId, hosts, channel);
        var slp = new StubbornLinkImpl(flp, hosts);
        var pl = new PerfectLinkImpl(myId, hosts, slp);

        // Must install handler *after* PL created
        initSignalHandlers(logger, pl);

        // On deliver: always log and periodically flush
        pl.onDeliver((sender, seq, data) -> {
            logger.logD(sender, seq);
            if (seq % LOG_INTERVAL == 0) {
                logger.flush();
                if (!MUTE) log("[deliver] from " + sender + " seq=" + seq);
            }
        });

        pl.start();

        if (myId != receiverId) {
            Host receiver = hosts.get(receiverId - 1);
            if (!MUTE)
                log("I am sender " + myId + ", sending " + m + " msgs to " + receiverId);

            for (int seq = 1; seq <= m; seq++) {
                logger.logB(seq);
                pl.send(receiver, new byte[0], seq);

                // Periodically flush and throttle
                if (seq % 1000 == 0) {
                    logger.flush();
                    Thread.sleep(1);
                }
            }

            logger.flush();  // flush remaining messages at the end
            if (!MUTE)
                log("Sender " + myId + " finished sending all messages.");
        } else {
            if (!MUTE)
                log("I am receiver " + myId + ", waiting for messages...");
        }

        Thread.sleep(Long.MAX_VALUE);

    }

}
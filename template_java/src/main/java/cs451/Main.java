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

    private static void handleSignal() {
        log("Immediately stopping network packet processing.");
        log("Writing output.");
    }

    private static void initSignalHandlers() {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                handleSignal();
            }
        });
    }

    public static void main(String[] args) throws Exception {
        Parser parser = new Parser(args);
        parser.parse();
        initSignalHandlers();

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

        // ---- Setup channel and layers ----
        var channel = UdpChannel.bind(me.getPort(), 0);
        var flp = new FairLossLinkImpl(myId, hosts, channel);
        var slp = new StubbornLinkImpl(flp, hosts);
        var pl = new PerfectLinkImpl(myId, hosts, slp);

        var logger = new LineLogger(parser.output());

        pl.onDeliver((sender, seq, data) -> {
            logger.logD(sender, seq);
            if (!MUTE && seq % LOG_INTERVAL == 0)
                log("[deliver] from " + sender + " seq=" + seq);
        });

        pl.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                pl.stop();
                logger.flush();
            } catch (Exception ignored) {}
        }));

        if (myId != receiverId) {
            // ---- Sender ----
            Host receiver = hosts.get(receiverId - 1);
            if (!MUTE)
                log("I am sender " + myId + ", sending " + m + " msgs to " + receiverId);

            for (int seq = 1; seq <= m; seq++) {
                logger.logB(seq);
                pl.send(receiver, new byte[0], seq);

                // Light throttling avoids overwhelming network in tests
                if (seq % 1000 == 0) Thread.sleep(1);
            }

            if (!MUTE)
                log("Sender " + myId + " finished sending all messages.");
        } else {
            // ---- Receiver ----
            if (!MUTE)
                log("I am receiver " + myId + ", waiting for messages...");
        }

        Thread.sleep(Long.MAX_VALUE);
    }

}
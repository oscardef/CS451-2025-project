package cs451;

import cs451.net.UdpChannel;
import cs451.links.fairloss.FairLossLinkImpl;
import cs451.links.stubborn.StubbornLinkImpl;
import cs451.links.perfect.PerfectLinkImpl;
import cs451.broadcast.urb.UniformReliableBroadcast;
import cs451.broadcast.urb.UniformReliableBroadcastImpl;
import cs451.broadcast.fifo.FifoBroadcast;
import cs451.broadcast.fifo.FifoBroadcastImpl;
import cs451.lattice.LatticeAgreement;
import cs451.lattice.LatticeConfigParser;
import cs451.util.LineLogger;

import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point for CS-451 Distributed Algorithms project.
 * 
 * Supports three milestones based on config format:
 *   - Two integers (m receiverId): Perfect Links
 *   - One integer (m): FIFO Broadcast
 *   - Three integers (p vs d): Lattice Agreement
 */
public final class Main {

    private static final boolean MUTE_CONSOLE = true;
    private static final int LOG_FLUSH_INTERVAL = 5000;
    private static final byte[] EMPTY_PAYLOAD = new byte[0];

    private static void log(String msg) {
        if (!MUTE_CONSOLE) System.out.println(msg);
    }

    private static void handleSignalM1(LineLogger logger, PerfectLinkImpl pl) {
        log("Stopping network processing...");
        try {
            pl.stop();
            logger.flush();
        } catch (Exception e) {
            System.err.println("[Shutdown] " + e.getMessage());
        }
        log("Shutdown complete.");
    }

    private static void handleSignalM2(LineLogger logger, FifoBroadcast frb) {
        log("Stopping network processing...");
        try {
            frb.stop();
            logger.flush();
        } catch (Exception e) {
            System.err.println("[Shutdown] " + e.getMessage());
        }
        log("Shutdown complete.");
    }

    private static void handleSignalM3(LineLogger logger, LatticeAgreement la) {
        log("Stopping network processing...");
        try {
            la.stop();
            logger.flush();
        } catch (Exception e) {
            System.err.println("[Shutdown] " + e.getMessage());
        }
        log("Shutdown complete.");
    }

    public static void main(String[] args) throws Exception {
        // Parse CLI arguments
        Parser parser = new Parser(args);
        parser.parse();

        int myId = parser.myId();
        List<Host> hosts = parser.hosts();
        Host me = hosts.get(myId - 1);

        // Parse config file to detect milestone
        int firstInt = 0;
        Integer secondInt = null;
        Integer thirdInt = null;
        try (Scanner sc = new Scanner(new java.io.File(parser.config()))) {
            if (sc.hasNextInt()) firstInt = sc.nextInt();
            if (sc.hasNextInt()) secondInt = sc.nextInt();
            if (sc.hasNextInt()) thirdInt = sc.nextInt();
        }

        // Detect milestone based on config format
        if (thirdInt != null) {
            // Milestone 3: Lattice Agreement (p vs d)
            runMilestone3(myId, hosts, me, parser.config(), parser.output());
        } else if (secondInt != null) {
            // Milestone 1: Perfect Links (m receiverId)
            runMilestone1(myId, hosts, me, firstInt, secondInt, parser.output());
        } else {
            // Milestone 2: FIFO Broadcast (m)
            runMilestone2(myId, hosts, me, firstInt, parser.output());
        }
    }

    private static void runMilestone1(int myId, List<Host> hosts, Host me, 
                                      int messageCount, int receiverId, String outputPath) throws Exception {
        // Setup logging and networking layers
        var logger = new LineLogger(outputPath);
        var udp = UdpChannel.bind(me.getPort(), 0);
        var flp = new FairLossLinkImpl(myId, hosts, udp);
        var slp = new StubbornLinkImpl(flp, hosts);
        var pl = new PerfectLinkImpl(myId, hosts, slp);

        // Setup shutdown handler
        Runtime.getRuntime().addShutdownHook(new Thread(() -> handleSignalM1(logger, pl)));

        // Register delivery callback
        final int[] deliveredCount = {0};
        pl.onDeliver((sender, seq, data) -> {
            logger.logD(sender, seq);
            if ((++deliveredCount[0] % LOG_FLUSH_INTERVAL) == 0) {
                logger.flush();
                if (!MUTE_CONSOLE) log(String.format("[M1 deliver] total=%,d", deliveredCount[0]));
            }
        });

        pl.start();

        /* Sender mode */
        if (myId != receiverId) {
            Host receiver = hosts.get(receiverId - 1);
            if (!MUTE_CONSOLE)
                log(String.format("[M1] Sender %d → Receiver %d | messages: %,d", myId, receiverId, messageCount));

            for (int seq = 1; seq <= messageCount; seq++) {
                logger.logB(seq);
                pl.send(receiver, EMPTY_PAYLOAD, seq);

                if ((seq % LOG_FLUSH_INTERVAL) == 0) {
                    logger.flush();
                }
            }

            logger.flush();
            if (!MUTE_CONSOLE)
                log(String.format("[M1] Sender %d finished %,d messages.", myId, messageCount));

        } else {
            /* Receiver mode */
            if (!MUTE_CONSOLE)
                log(String.format("[M1] Receiver %d waiting for messages...", myId));
        }

        // Keep alive
        synchronized (Main.class) {
            Main.class.wait();
        }
    }

    private static void runMilestone2(int myId, List<Host> hosts, Host me, 
                                      int messageCount, String outputPath) throws Exception {
        // Setup logging
        var logger = new LineLogger(outputPath);
        
        // Setup networking layers: FRB → URB → PL → SL → FLP → UDP
        var udp = UdpChannel.bind(me.getPort(), 0);
        var flp = new FairLossLinkImpl(myId, hosts, udp);
        var slp = new StubbornLinkImpl(flp, hosts);
        var pl = new PerfectLinkImpl(myId, hosts, slp);
        var urb = new UniformReliableBroadcastImpl(myId, hosts, pl);
        var frb = new FifoBroadcastImpl(myId, hosts.size(), urb);

        // Setup shutdown handler
        Runtime.getRuntime().addShutdownHook(new Thread(() -> handleSignalM2(logger, frb)));

        // Register FRB delivery callback
        final int[] deliveredCount = {0};
        frb.onDeliver((sender, seq) -> {
            logger.logD(sender, seq);
            if ((++deliveredCount[0] % LOG_FLUSH_INTERVAL) == 0) {
                logger.flush();
                if (!MUTE_CONSOLE) log(String.format("[M2 deliver] total=%,d", deliveredCount[0]));
            }
        });

        // Start the protocol stack
        frb.start();

        if (!MUTE_CONSOLE)
            log(String.format("[M2] Process %d broadcasting %,d messages...", myId, messageCount));

        // Spawn broadcaster thread to avoid blocking
        Thread broadcaster = new Thread(() -> {
            for (int seq = 1; seq <= messageCount; seq++) {
                logger.logB(seq);
                frb.broadcast(seq);

                if ((seq % LOG_FLUSH_INTERVAL) == 0) {
                    logger.flush();
                }
            }

            logger.flush();
            if (!MUTE_CONSOLE)
                log(String.format("[M2] Process %d finished broadcasting %,d messages.", myId, messageCount));
        }, "broadcaster");

        broadcaster.start();

        // Keep alive
        synchronized (Main.class) {
            Main.class.wait();
        }
    }

    private static void runMilestone3(int myId, List<Host> hosts, Host me,
                                      String configPath, String outputPath) throws Exception {
        // Parse lattice agreement config
        var configParser = new LatticeConfigParser();
        configParser.parse(configPath);
        
        int numProposals = configParser.getNumProposals();
        List<Set<Integer>> proposals = configParser.getProposals();
        
        // Setup logging
        var logger = new LineLogger(outputPath);
        
        // Setup networking layers: LA → PL → SL → FLP → UDP
        var udp = UdpChannel.bind(me.getPort(), 0);
        var flp = new FairLossLinkImpl(myId, hosts, udp);
        var slp = new StubbornLinkImpl(flp, hosts);
        var pl = new PerfectLinkImpl(myId, hosts, slp);
        
        // Create lattice agreement layer
        var la = new LatticeAgreement(myId, hosts, pl, numProposals);
        
        // Setup shutdown handler
        Runtime.getRuntime().addShutdownHook(new Thread(() -> handleSignalM3(logger, la)));

        // Track decisions for ordered logging
        final Set<Integer>[] decisions = new Set[numProposals];
        final int[] nextToLog = {0};
        final Object logLock = new Object();

        // Register decision callback
        la.onDecide((agreementId, values) -> {
            synchronized (logLock) {
                decisions[agreementId] = values;
                // Log decisions in order (agreement 0, 1, 2, ...)
                while (nextToLog[0] < numProposals && decisions[nextToLog[0]] != null) {
                    logger.logDecision(decisions[nextToLog[0]]);
                    if ((nextToLog[0] + 1) % LOG_FLUSH_INTERVAL == 0) {
                        logger.flush();
                    }
                    nextToLog[0]++;
                }
            }
        });

        // Start the protocol stack
        la.start();

        if (!MUTE_CONSOLE)
            log(String.format("[M3] Process %d starting %d lattice agreements...", myId, numProposals));

        // Spawn proposer thread
        Thread proposer = new Thread(() -> {
            try {
                for (int i = 0; i < numProposals; i++) {
                    // Propose value for agreement i
                    la.propose(i, proposals.get(i));
                    
                    // Wait for decision before next proposal (ensures ordered decisions)
                    la.awaitDecision(i);
                    
                    if (!MUTE_CONSOLE && (i + 1) % 100 == 0) {
                        log(String.format("[M3] Process %d completed %d agreements", myId, i + 1));
                    }
                }
                
                logger.flush();
                if (!MUTE_CONSOLE)
                    log(String.format("[M3] Process %d finished all %d agreements.", myId, numProposals));
                    
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "proposer");

        proposer.start();

        // Keep alive
        synchronized (Main.class) {
            Main.class.wait();
        }
    }
}

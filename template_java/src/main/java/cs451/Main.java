package cs451;


import cs451.net.UdpChannel;
import cs451.pl.impl.StopAndWaitPL;
import cs451.util.LineLogger;

import java.net.SocketException;

public class Main {

    private static void handleSignal() {
        //immediately stop network packet processing
        System.out.println("Immediately stopping network packet processing.");

        //write/flush output file if necessary
        System.out.println("Writing output.");
    }

    private static void initSignalHandlers() {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                handleSignal();
            }
        });
    }

    public static void main(String[] args) throws InterruptedException, SocketException {
        Parser parser = new Parser(args);
        parser.parse();

        initSignalHandlers();

        long pid = ProcessHandle.current().pid();
        System.out.println("My PID: " + pid + "\n");
        System.out.println("From another terminal type `kill -SIGINT " + pid +
                "` or `kill -SIGTERM " + pid + "` to stop processing packets\n");

        System.out.println("My ID: " + parser.myId() + "\n");
        System.out.println("List of resolved hosts is:");
        System.out.println("==========================");
        for (Host host : parser.hosts()) {
            System.out.println(host.getId());
            System.out.println("Human-readable IP: " + host.getIp());
            System.out.println("Human-readable Port: " + host.getPort());
            System.out.println();
        }
        System.out.println();

        System.out.println("Path to output:");
        System.out.println("===============");
        System.out.println(parser.output() + "\n");

        System.out.println("Path to config:");
        System.out.println("===============");
        System.out.println(parser.config() + "\n");

        System.out.println("Doing some initialization...\n");

        // ---- Step 1: Build the UDP channel (bind to my port) ----
        int myId = parser.myId();
        var hosts = parser.hosts();
        var me = hosts.get(myId - 1);

        var channel = UdpChannel.bind(me.getPort(), /*soTimeoutMillis=*/0);

        // ---- Step 2: Create Perfect Link instance ----
        var pl = new StopAndWaitPL(myId, hosts, channel);

        // ---- Step 3: Set up logger ----
        // LineLogger writes "b <id> <seq>" for broadcasts and "d <id> <seq>" for deliveries.
        var logger = new LineLogger(parser.output());

        // ---- Step 4: Register delivery callback ----
        pl.onDeliver((sender, seq) -> {
            logger.logD(sender, seq);  // Log a delivery event
            System.out.println("[deliver] from " + sender + " seq=" + seq);
        });

        // ---- Step 5: Start the Perfect Link ----
        pl.start();

        // Gracefully stop PL and flush logs on process termination
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                pl.stop();
                logger.flush();  // Ensure all lines are written before exit
            } catch (Exception ignored) {}
        }));

        System.out.println("Broadcasting and delivering messages...\n");

        // ---- Step 6: Example broadcast test ----
        // Only process 1 sends messages in this simple test
        if (myId == 1) {
            for (int seq = 1; seq <= 5; seq++) {
                logger.logB(seq); // log once per broadcast
                // Send to all other processes
                for (Host h : hosts) {
                    if (h.getId() == myId) continue;
                    System.out.println("[test] sending seq=" + seq + " to process " + h.getId());
                    pl.send(h.getId(), seq);
                }
                Thread.sleep(200); // small delay between sends for readability
            }
        }

        // Keep running indefinitely (terminated via kill)
        Thread.sleep(Long.MAX_VALUE);
    }
}
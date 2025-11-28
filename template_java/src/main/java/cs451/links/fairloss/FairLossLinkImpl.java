package cs451.links.fairloss;

import cs451.Host;
import cs451.net.UdpChannel;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FairLossLinkImpl — UDP-based best-effort transport layer.
 *
 * Responsibilities:
 *  - Handle packet sending/receiving with no guarantees (Fair-Loss model).
 *  - Maintain a bounded send queue to prevent unbounded memory growth.
 *  - Provide isolation between network I/O and upper layers.
 *
 * Optimizations and Fixes:
 *  - Blocking queue (no silent drops).
 *  - Large capacity (1M entries) for high-throughput workloads.
 *  - Pre-resolved host addresses for fast lookup.
 *  - Clean thread termination and error handling.
 */
public final class FairLossLinkImpl implements FairLossLink {

    private final int myId;
    private final List<Host> membership;
    private final UdpChannel channel;

    private final InetAddress[] idToAddr;
    private final int[] idToPort;
    private final Map<String, Integer> addrPortToId = new HashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread rxThread, txThread;

    private volatile DeliverHandler deliverHandler = (src, data) -> {};

    /** Large bounded send queue — blocks instead of dropping packets. */
    private final BlockingQueue<Packet> sendQ = new ArrayBlockingQueue<>(1_000_000);

    /** Represents one pending UDP datagram. */
    private static final class Packet {
        final InetAddress addr;
        final int port;
        final byte[] data;
        Packet(InetAddress a, int p, byte[] d) { addr = a; port = p; data = d; }
    }

    public FairLossLinkImpl(int myId, List<Host> membership, UdpChannel channel) {
        this.myId = myId;
        this.membership = membership;
        this.channel = channel;

        this.idToAddr = new InetAddress[membership.size() + 1];
        this.idToPort = new int[membership.size() + 1];

        for (Host h : membership) {
            try {
                InetAddress addr = InetAddress.getByName(h.getIp());
                idToAddr[h.getId()] = addr;
                idToPort[h.getId()] = h.getPort();
                addrPortToId.put(addr.getHostAddress() + ":" + h.getPort(), h.getId());
            } catch (UnknownHostException e) {
                throw new RuntimeException("Cannot resolve host " + h.getIp(), e);
            }
        }
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;

        // Start RX thread
        rxThread = new Thread(this::rxLoop, "flp-rx");
        rxThread.setDaemon(true);
        rxThread.start();

        // Start TX thread
        txThread = new Thread(this::txLoop, "flp-tx");
        txThread.setDaemon(true);
        txThread.start();
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        try {
            channel.close();
        } catch (Exception ignored) {}
        if (rxThread != null) rxThread.interrupt();
        if (txThread != null) txThread.interrupt();
    }

    @Override
    public void send(Host dest, byte[] data) {
        InetAddress addr = idToAddr[dest.getId()];
        int port = idToPort[dest.getId()];
        if (addr == null) return;

        Packet pkt = new Packet(addr, port, data);
        try {
            // Block instead of dropping when queue full (natural backpressure)
            sendQ.put(pkt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onDeliver(DeliverHandler handler) {
        this.deliverHandler = handler;
    }

    /** Dedicated TX thread — sends queued packets as fast as possible. */
    private void txLoop() {
        while (running.get()) {
            try {
                Packet p = sendQ.take(); // blocking take ensures fairness
                channel.send(p.data, p.data.length, p.addr, p.port);
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[FLP] TX error: " + e.getMessage());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Dedicated RX thread — single allocation buffer reused for performance. */
    private void rxLoop() {
        final byte[] buf = new byte[65535];
        final UdpChannel.SenderRef src = new UdpChannel.SenderRef();

        while (running.get()) {
            try {
                int n = channel.receive(buf, src);
                if (n <= 0) continue;

                Integer senderId = addrPortToId.get(src.address.getHostAddress() + ":" + src.port);
                if (senderId != null) {
                    // Copy the received payload before reuse of buffer
                    byte[] msg = Arrays.copyOf(buf, n);
                    deliverHandler.deliver(senderId, msg);
                }
            } catch (IOException e) {
                if (running.get() && !e.getMessage().contains("Socket closed")) {
                    System.err.println("[FLP] RX error: " + e.getMessage());
                }
            } catch (Exception e) {
                if (running.get()) {
                    System.err.println("[FLP] Unexpected RX error: " + e.getMessage());
                }
            }
        }
    }
}

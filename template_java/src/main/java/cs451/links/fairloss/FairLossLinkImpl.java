package cs451.links.fairloss;

import cs451.Host;
import cs451.net.UdpChannel;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FairLossLinkImpl — Implements the Fair-Loss Point-to-Point Link (FLP) abstraction.
 *
 * ---------------------------------------------------------------
 * This layer provides *best-effort* delivery using UDP.
 * ---------------------------------------------------------------
 * Guarantees:
 *  - Messages may be lost, duplicated, or delayed (fair-loss model)
 *  - No creation: a process only delivers messages that were actually sent
 *
 * Used by: StubbornLinkImpl (upper layer)
 * Underlying: UdpChannel (UDP socket wrapper)
 *
 * Based on the pseudocode from the slides:
 *  - Send: simply trigger a network send
 *  - Deliver: whenever a UDP packet is received, pass it up
 */
public class FairLossLinkImpl implements FairLossLink {

    // ---- Construction-time inputs ----
    private final int myId;
    private final List<Host> membership;
    private final UdpChannel channel;

    // ---- Lookups for destination info ----
    private final InetAddress[] idToAddr;
    private final int[] idToPort;

    // ---- Control flags ----
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread rxThread;

    // ---- Delivery handler (registered by upper layer) ----
    private volatile DeliverHandler deliverHandler = (src, data) -> {};

    public FairLossLinkImpl(int myId, List<Host> membership, UdpChannel channel) {
        this.myId = myId;
        this.membership = membership;
        this.channel = channel;

        this.idToAddr = new InetAddress[membership.size() + 1]; // +1 to make indexing by ID easier since IDs start at 1
        this.idToPort = new int[membership.size() + 1];         // +1 to make indexing by ID easier since IDs start at 1

        // Build lookup tables for (id -> address/port)
        for (Host h : membership) {
            try {
                idToAddr[h.getId()] = InetAddress.getByName(h.getIp());
                idToPort[h.getId()] = h.getPort();
            } catch (UnknownHostException e) {
                throw new RuntimeException("Cannot resolve host " + h.getIp(), e);
            }
        }
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return; // if already running, do nothing

        // Start receiver thread for incoming UDP messages
        rxThread = new Thread(this::rxLoop, "flp-rx");
        rxThread.setDaemon(true);
        rxThread.start();
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        channel.close();
        try {
            if (rxThread != null) rxThread.join();
        } catch (InterruptedException ignored) {}
    }

    @Override
    public void send(Host dest, byte[] data) {
        try {
            channel.send(data, data.length,
                    idToAddr[dest.getId()],
                    idToPort[dest.getId()]);
        } catch (IOException e) {
            System.err.println("[FLP] send failed to " + dest.getId() + ": " + e.getMessage());
        }
    }

    @Override
    public void onDeliver(DeliverHandler handler) {
        this.deliverHandler = handler;
    }

    /**
     * Background loop that continuously receives UDP packets and triggers delivery events.
     */
    private void rxLoop() {
        byte[] buf = new byte[1024]; // FLP doesn't know message size yet — will adjust later
        UdpChannel.SenderRef src = new UdpChannel.SenderRef();

        while (running.get()) {
            try {
                int n = channel.receive(buf, src);
                if (n <= 0) continue;

                int senderId = findSenderId(src);
                if (senderId != -1) {       // only deliver if sender is known
                    byte[] msgCopy = new byte[n];
                    System.arraycopy(buf, 0, msgCopy, 0, n);
                    deliverHandler.deliver(senderId, msgCopy);
                }

            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[FLP] receive error: " + e.getMessage());
                }
            }
        }
    }

    /**
     * Helper: find which host ID matches a sender's address and port.
     */
    private int findSenderId(UdpChannel.SenderRef src) {
        for (Host h : membership) {
            // Compare using address string to avoid DNS mismatches
            if (h.getPort() == src.port &&
                h.getIp().equals(src.address.getHostAddress())) {
                return h.getId();
            }
        }
        return -1; // unknown sender — ignore
    }
}

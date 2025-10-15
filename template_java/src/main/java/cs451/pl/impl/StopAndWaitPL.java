package cs451.pl.impl;

import cs451.Host;
import cs451.net.UdpChannel;
import cs451.pl.DeliverCallback;
import cs451.pl.Message;
import cs451.pl.PerfectLink;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * StopAndWaitPL — Milestone 1 Perfect Links using stop-and-wait per message.
 * Now includes: deduplication + ACK sending + retransmission on timeout.
 */
public final class StopAndWaitPL implements PerfectLink {

    // ---- Construction-time inputs ----
    private final int myId;
    private final List<Host> membership;          // index by (id-1)
    private final UdpChannel channel;

    // ---- Derived lookups ----
    private final InetAddress[] idToAddr;         // id -> InetAddress
    private final int[] idToPort;                 // id -> UDP port

    // ---- Threads & control ----
    private final AtomicBoolean running = new AtomicBoolean(false);     // flipped to stop threads
    private Thread rxThread;        // receives and processes incoming packets
    private Thread txThread;        // periodically checks for timeouts and retransmits

    // ---- Delivery callback ----
    private volatile DeliverCallback deliverCb = (sender, seq) -> {};   // no-op until onDeliver() is called

    // ---- Serialization buffer (reused to avoid allocations) ----
    private final ThreadLocal<ByteBuffer> threadLocalSendBuf =
            ThreadLocal.withInitial(() -> ByteBuffer.allocate(Message.byteSize())); // per-thread ByteBuffer for sending

    // ---- Deduplication set ----
    private final Set<Long> delivered = ConcurrentHashMap.newKeySet();  // (senderId << 32) | seq

    // ---- NEW: Outstanding send table ----
    // For each destination, remember the last message awaiting ACK.
    private static final class Pending {
        final byte[] bytes;      // serialized message bytes
        final int destId;        // destination process ID
        final int seq;           // sequence number
        volatile long lastSent;  // last time (ms) we sent this message
        Pending(byte[] bytes, int destId, int seq) {
            this.bytes = bytes;
            this.destId = destId;
            this.seq = seq;
            this.lastSent = System.currentTimeMillis();
        }
    }
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    // key = (destId << 32) | seq

    // ---- Retransmission settings ----
    private static final long RETRANSMIT_INTERVAL_MS = 50;  // resend check frequency
    private static final long TIMEOUT_MS = 100;             // resend after 100ms of silence

    public StopAndWaitPL(int myId, List<Host> membership, UdpChannel channel) {
        this.myId = myId;
        this.membership = membership;
        this.channel = channel;

        this.idToAddr = new InetAddress[membership.size() + 1];
        this.idToPort = new int[membership.size() + 1];

        // Build id -> (InetAddress, port) tables
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
        if (!running.compareAndSet(false, true)) return;

        // Start receiver thread
        rxThread = new Thread(this::rxLoop, "pl-rx");
        rxThread.setDaemon(true);
        rxThread.start();

        // NEW: Start retransmission thread
        txThread = new Thread(this::txLoop, "pl-tx");
        txThread.setDaemon(true);
        txThread.start();
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        channel.close();
        try {
            if (rxThread != null) rxThread.join();
            if (txThread != null) txThread.join();
        } catch (InterruptedException ignored) {}
    }

    @Override
    public void onDeliver(DeliverCallback callback) {
        this.deliverCb = Objects.requireNonNull(callback);
    }

    @Override
    public void send(int destId, int seq) {
        // --- Construct the DATA message and serialize it ---
        Message msg = new Message(Message.TYPE_DATA, myId, destId, seq);
        ByteBuffer bb = threadLocalSendBuf.get();
        bb.clear();
        msg.writeTo(bb);
        byte[] bytes = Arrays.copyOf(bb.array(), Message.byteSize());

        // --- Send immediately once ---
        try {
            channel.send(bytes, Message.byteSize(), idToAddr[destId], idToPort[destId]);
        } catch (IOException e) {
            System.err.println("[pl] send failed: " + e.getMessage());
        }

        // --- Remember this message as pending ---
        long key = ((long) destId << 32) | (seq & 0xFFFFFFFFL);
        pending.put(key, new Pending(bytes, destId, seq));
    }

    /** Receiver thread: handle DATA + ACK packets. */
    private void rxLoop() {
        byte[] buf = new byte[Message.byteSize()];
        UdpChannel.SenderRef src = new UdpChannel.SenderRef();

        while (running.get()) {
            try {
                int n = channel.receive(buf, src);
                if (n < Message.byteSize()) continue;

                Message m = Message.readFrom(ByteBuffer.wrap(buf, 0, Message.byteSize()));
                if (m.destId != myId || m.senderId <= 0 || m.senderId >= idToAddr.length) continue;

                if (m.type == Message.TYPE_DATA) {
                    // ---- Always ACK back ----
                    sendAck(m.senderId, m.seq);

                    // ---- Deduplicate ----
                    long key = ((long) m.senderId << 32) | (m.seq & 0xFFFFFFFFL);
                    if (delivered.add(key)) {
                        deliverCb.deliver(m.senderId, m.seq);
                        System.out.println("[rx] delivered DATA from " + m.senderId + " seq=" + m.seq);
                    }

                } else if (m.type == Message.TYPE_ACK) {
                    // ---- ACK received → mark as delivered to stop retransmission ----
                    long key = ((long) m.senderId << 32) | (m.seq & 0xFFFFFFFFL);
                    Pending p = pending.remove(key);
                    if (p != null) {
                        System.out.println("[rx] ACK from " + m.senderId + " seq=" + m.seq + " (cleared pending)");
                    }
                }
            } catch (IOException e) {
                if (running.get()) {
                    System.err.println("[pl] receive error: " + e.getMessage());
                }
            }
        }
    }

    /** Sender thread: periodically checks for unacknowledged messages and resends them. */
    private void txLoop() {
        while (running.get()) {
            try {
                long now = System.currentTimeMillis();
                for (Pending p : pending.values()) {
                    if (now - p.lastSent > TIMEOUT_MS) {
                        try {
                            channel.send(p.bytes, Message.byteSize(),
                                         idToAddr[p.destId], idToPort[p.destId]);
                            p.lastSent = now;
                            System.out.println("[tx] retransmitted seq=" + p.seq + " to " + p.destId);
                        } catch (IOException e) {
                            System.err.println("[tx] resend failed: " + e.getMessage());
                        }
                    }
                }
                Thread.sleep(RETRANSMIT_INTERVAL_MS);
            } catch (InterruptedException ignored) {}
        }
    }

    /** Helper function to send an ACK message to the specified destination. */
    private void sendAck(int destId, int seq) {
        Message ack = new Message(Message.TYPE_ACK, myId, destId, seq);
        ByteBuffer bb = threadLocalSendBuf.get();
        bb.clear();
        ack.writeTo(bb);
        byte[] bytes = bb.array();
        try {
            channel.send(bytes, Message.byteSize(), idToAddr[destId], idToPort[destId]);
        } catch (IOException e) {
            System.err.println("[pl] send ACK failed: " + e.getMessage());
        }
    }
}

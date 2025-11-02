package cs451.links.perfect;

import cs451.Host;
import cs451.links.stubborn.StubbornLink;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PerfectLinkImpl — Reliable, exactly-once delivery on top of Stubborn Link.
 *
 * Key points:
 *  - Uses (senderId, seq) as a 64-bit key for dedup + ACK tracking.
 *  - Batches DATA messages (no background flush thread):
 *      * Flushes when batch hits MAX_BATCH
 *      * Also soft-flushes every SOFT_FLUSH_EVERY sends (counter-based)
 *  - ACKs are sent as single messages (simple and fast enough).
 */
public final class PerfectLinkImpl implements PerfectLink {

    // ---- Lower layer / membership ----
    private final StubbornLink slp;
    private final List<Host> membership;
    private final int myId;

    // ---- Control ----
    private final AtomicBoolean running = new AtomicBoolean(false);

    // ---- Bookkeeping ----
    private final Set<Long> delivered        = ConcurrentHashMap.newKeySet();
    private final Set<Long> awaitingAck      = ConcurrentHashMap.newKeySet();
    private final Map<Long, byte[]> sentMsgs = new ConcurrentHashMap<>();

    // ---- Delivery callback ----
    private volatile DeliverHandler deliverHandler = (sender, seq, data) -> {};

    // ---- Wire protocol ----
    private static final byte TYPE_DATA  = 1;
    private static final byte TYPE_ACK   = 2;
    private static final byte TYPE_BATCH = 3;

    // ---- Batching (no background thread) ----
    private static final int MAX_BATCH = 8;        // messages per datagram
    private static final int SOFT_FLUSH_MASK = (1 << 13) - 1; // 8192-1 (power-of-two mask)

    private final Map<Integer, ArrayDeque<byte[]>> batches = new ConcurrentHashMap<>();
    private final AtomicInteger sendCounter = new AtomicInteger(0);

    public PerfectLinkImpl(int myId, List<Host> membership, StubbornLink slp) {
        this.myId = myId;
        this.membership = membership;
        this.slp = slp;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        slp.start();
        slp.onDeliver(this::handleReceive);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        // Flush any residual batches so no messages are stuck
        flushAllBatches();
        slp.stop();
    }

    @Override
    public void send(Host dest, byte[] data, int seq) {
        // Build DATA message bytes once
        byte[] msg = encode(TYPE_DATA, myId, seq, data);
        long k = key(dest.getId(), seq);
        awaitingAck.add(k);
        sentMsgs.put(k, msg);

        // Put into the dest's batch (create deque if missing)
        ArrayDeque<byte[]> q = batches.computeIfAbsent(dest.getId(), id -> new ArrayDeque<>(MAX_BATCH));
        synchronized (q) {
            q.addLast(msg);
            if (q.size() >= MAX_BATCH) {
                flushBatch(dest, q);
            }
        }

        // Soft flush every ~8k sends to avoid long tails without extra threads
        if ((sendCounter.incrementAndGet() & SOFT_FLUSH_MASK) == 0) {
            flushAllBatches();
        }
    }

    @Override
    public void onDeliver(DeliverHandler handler) {
        this.deliverHandler = handler;
    }

    /** Flush all partial batches (called on stop, and periodically via counter). */
    private void flushAllBatches() {
        for (Map.Entry<Integer, ArrayDeque<byte[]>> e : batches.entrySet()) {
            Host dest = membership.get(e.getKey() - 1);
            ArrayDeque<byte[]> q = e.getValue();
            synchronized (q) {
                if (!q.isEmpty()) flushBatch(dest, q);
            }
        }
    }

    /** Build and send one TYPE_BATCH datagram for the queued messages. */
    private void flushBatch(Host dest, ArrayDeque<byte[]> q) {
        if (q.isEmpty()) return;

        int count = q.size();
        int total = 1 + 4; // type + count
        for (byte[] m : q) total += 4 + m.length;

        ByteBuffer bb = ByteBuffer.allocate(total);
        bb.put(TYPE_BATCH);
        bb.putInt(count);
        for (byte[] m : q) {
            bb.putInt(m.length);
            bb.put(m);
        }

        slp.send(dest, bb.array());
        q.clear();
    }

    /** Handle bytes coming up from SLP (could be single or batch). */
    private void handleReceive(int senderId, byte[] raw) {
        if (raw == null || raw.length == 0) return;

        byte first = raw[0];
        if (first == TYPE_BATCH) {
            // [TYPE_BATCH(1) | count(4) | (len(4) | msg[len])+ ]
            if (raw.length < 5) return;
            ByteBuffer bb = ByteBuffer.wrap(raw);
            bb.get(); // type
            int count = bb.getInt();
            for (int i = 0; i < count; i++) {
                if (bb.remaining() < 4) break;
                int len = bb.getInt();
                if (len < 1 || len > bb.remaining()) break;
                byte[] msg = new byte[len];
                bb.get(msg);
                handleSingle(senderId, msg);
            }
        } else {
            handleSingle(senderId, raw);
        }
    }

    /** Decode one logical message (DATA/ACK). */
    private void handleSingle(int senderId, byte[] raw) {
        if (raw.length < 9) return; // type(1) + sender(4) + seq(4)
        ByteBuffer bb = ByteBuffer.wrap(raw);

        byte type = bb.get();
        int sender = bb.getInt();
        int seq = bb.getInt();

        byte[] payload = new byte[Math.max(0, bb.remaining())];
        if (payload.length > 0) bb.get(payload);

        if (type == TYPE_DATA) {
            long k = key(sender, seq);
            if (delivered.add(k)) {
                deliverHandler.deliver(sender, seq, payload);
            }
            // ACK back to sender
            Host senderHost = membership.get(sender - 1);
            byte[] ack = encode(TYPE_ACK, myId, seq, new byte[0]);
            slp.send(senderHost, ack);

        } else if (type == TYPE_ACK) {
            // ACK sender == our original dest, that matches awaitingAck key
            long k = key(sender, seq);
            awaitingAck.remove(k);
            byte[] msg = sentMsgs.remove(k);
            if (msg != null) {
                Host senderHost = membership.get(sender - 1);
                slp.remove(senderHost, msg);
            }
        }
    }

    /** Encode: [type(1) | sender(4) | seq(4) | payload...] */
    private static byte[] encode(byte type, int senderId, int seq, byte[] data) {
        ByteBuffer bb = ByteBuffer.allocate(1 + 4 + 4 + data.length);
        bb.put(type);
        bb.putInt(senderId);
        bb.putInt(seq);
        bb.put(data);
        return bb.array();
    }

    /** 64-bit key for (id,seq). */
    private static long key(int sender, int seq) {
        return (((long) sender) << 32) | (seq & 0xffffffffL);
    }
}

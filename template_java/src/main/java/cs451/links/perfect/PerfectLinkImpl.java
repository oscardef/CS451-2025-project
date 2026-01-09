package cs451.links.perfect;

import cs451.Host;
import cs451.links.stubborn.StubbornLink;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Perfect Links with message batching.
 * 
 * Sends ACKs to stop retransmission, deduplicates on delivery.
 */
public final class PerfectLinkImpl implements PerfectLink {

    private final StubbornLink slp;
    private final List<Host> membership;
    private final int myId;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final Set<Long> delivered = ConcurrentHashMap.newKeySet();
    private final Map<Long, byte[]> sentMessages = new ConcurrentHashMap<>();
    private final Set<Long> awaitingAck = ConcurrentHashMap.newKeySet();

    private volatile DeliverHandler deliverHandler = (sender, seq, data) -> {};

    private static final byte TYPE_DATA  = 1;
    private static final byte TYPE_ACK   = 2;
    private static final byte TYPE_BATCH = 3;

    private static final int MAX_BATCH = 8;
    private final int flushIntervalMs;

    private final Map<Integer, List<byte[]>> batchBuffers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService batchFlusher =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "batch-flusher");
                t.setDaemon(true);
                return t;
            });

    public PerfectLinkImpl(int myId, List<Host> membership, StubbornLink slp) {
        this(myId, membership, slp, 2);
    }

    public PerfectLinkImpl(int myId, List<Host> membership, StubbornLink slp, int flushIntervalMs) {
        this.myId = myId;
        this.membership = membership;
        this.slp = slp;
        this.flushIntervalMs = flushIntervalMs;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        slp.start();
        slp.onDeliver(this::handleReceive);

        // periodic flush of partial batches
        batchFlusher.scheduleAtFixedRate(this::flushAllBatches,
                flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        
        // Count unflushed messages for debugging
        int unflushed = 0;
        for (List<byte[]> batch : batchBuffers.values()) {
            synchronized (batch) {
                unflushed += batch.size();
            }
        }
        if (unflushed > 0) {
            System.err.println("[PerfectLink] Flushing " + unflushed + " pending messages before shutdown");
        }
        
        // CRITICAL: Flush all pending batches before stopping lower layers
        flushAllBatches();
        
        batchFlusher.shutdownNow();
        try {
            batchFlusher.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        
        slp.stop();
    }

    @Override
    public void send(Host dest, byte[] data, int seq) {
        byte[] msg = encodeMessage(TYPE_DATA, myId, seq, data);
        
        long stubbornKey = (((long) dest.getId()) << 32) ^ Arrays.hashCode(msg);
        awaitingAck.add(stubbornKey);
        sentMessages.put(stubbornKey, msg);

        List<byte[]> batch = batchBuffers.computeIfAbsent(dest.getId(),
                id -> Collections.synchronizedList(new ArrayList<>(MAX_BATCH)));

        List<byte[]> toFlush = null;
        synchronized (batch) {
            batch.add(msg);
            if (batch.size() >= MAX_BATCH) {
                toFlush = new ArrayList<>(batch);
                batch.clear();
            }
        }
        
        if (toFlush != null) {
            flushBatchDirect(dest, toFlush);
        }
    }

    @Override
    public void onDeliver(DeliverHandler handler) {
        this.deliverHandler = handler;
    }

    private void flushAllBatches() {
        for (Map.Entry<Integer, List<byte[]>> e : batchBuffers.entrySet()) {
            Host dest = membership.get(e.getKey() - 1);
            List<byte[]> batch = e.getValue();
            synchronized (batch) {
                if (!batch.isEmpty()) flushBatch(dest, batch);
            }
        }
    }

    private void flushBatch(Host dest, List<byte[]> batch) {
        synchronized (batch) {
            if (batch.isEmpty()) return;
            List<byte[]> toFlush = new ArrayList<>(batch);
            batch.clear();
            flushBatchDirect(dest, toFlush);
        }
    }
    
    private void flushBatchDirect(Host dest, List<byte[]> messages) {
        if (messages.isEmpty()) return;

        int total = 1 + 4; // type + count
        for (byte[] m : messages) total += 4 + m.length;
        ByteBuffer bb = ByteBuffer.allocate(total);

        bb.put(TYPE_BATCH);
        bb.putInt(messages.size());
        for (byte[] m : messages) {
            bb.putInt(m.length);
            bb.put(m);
        }

        slp.send(dest, bb.array());
    }

    private void handleReceive(int senderId, byte[] raw) {
        if (raw == null || raw.length < 2) return;
        ByteBuffer bb = ByteBuffer.wrap(raw);
        byte type = bb.get();

        if (type == TYPE_BATCH) {
            if (bb.remaining() < 4) return;
            int count = bb.getInt();
            for (int i = 0; i < count && bb.remaining() >= 4; i++) {
                int len = bb.getInt();
                if (len <= 0 || len > bb.remaining()) break;
                byte[] msg = new byte[len];
                bb.get(msg);
                handleSingleMessage(senderId, msg);
            }
        } else {
            handleSingleMessage(senderId, raw);
        }
    }

    private void handleSingleMessage(int senderId, byte[] raw) {
        if (raw == null || raw.length < 9) return;
        ByteBuffer bb = ByteBuffer.wrap(raw);
        byte type = bb.get();
        int sender = bb.getInt();
        int seq = bb.getInt();

        byte[] payload = new byte[Math.max(0, bb.remaining())];
        bb.get(payload);

        if (type == TYPE_DATA) {
            long k = key(sender, seq);
            if (delivered.add(k)) {
                deliverHandler.deliver(sender, seq, payload);
            }

            // Send ACK once (unreliable)
            Host senderHost = membership.get(sender - 1);
            byte[] ack = encodeMessage(TYPE_ACK, myId, seq, new byte[0]);
            if (slp instanceof cs451.links.stubborn.StubbornLinkImpl) {
                ((cs451.links.stubborn.StubbornLinkImpl) slp).sendOnce(senderHost, ack);
            } else {
                slp.send(senderHost, ack);
            }

        } else if (type == TYPE_ACK) {
            byte[] originalMsg = encodeMessage(TYPE_DATA, sender, seq, payload);
            long stubbornKey = (((long) sender) << 32) ^ Arrays.hashCode(originalMsg);
            
            if (awaitingAck.remove(stubbornKey)) {
                byte[] msg = sentMessages.remove(stubbornKey);
                if (msg != null) {
                    Host senderHost = membership.get(sender - 1);
                    slp.remove(senderHost, msg);
                }
            }
        }
    }

    private byte[] encodeMessage(byte type, int senderId, int seq, byte[] data) {
        ByteBuffer bb = ByteBuffer.allocate(1 + 4 + 4 + data.length);
        bb.put(type);
        bb.putInt(senderId);
        bb.putInt(seq);
        bb.put(data);
        return bb.array();
    }

    private static long key(int sender, int seq) {
        return (((long) sender) << 32) | (seq & 0xffffffffL);
    }
}

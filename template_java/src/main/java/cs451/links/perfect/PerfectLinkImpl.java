package cs451.links.perfect;

import cs451.Host;
import cs451.links.stubborn.StubbornLink;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PerfectLinkImpl — Implements Reliable, Exactly-Once Message Delivery.
 *
 * ----------------------------------------------------------------------
 * Layering:
 *     PerfectLinkImpl  →  StubbornLinkImpl  →  FairLossLinkImpl  →  UDP
 * ----------------------------------------------------------------------
 * Based on course pseudocode:
 *
 * upon p2pSend(m, q):
 *     trigger <slpSend, (DATA, m), q>
 *
 * upon <slpDeliver, q, (DATA, m)>:
 *     if (q, m) not yet delivered:
 *         deliver(m)
 *         trigger <slpSend, (ACK, m), q>
 *
 * upon <slpDeliver, q, (ACK, m)>:
 *     stop retransmitting (DATA, m)
 *
 * ----------------------------------------------------------------------
 * Guarantees:
 *  No message loss (eventually delivered if sent infinitely often)
 *  No duplication (each (sender,seq) delivered at most once)
 *  No creation (only delivered if sent)
 */
public final class PerfectLinkImpl implements PerfectLink {

    // ---- Lower layer ----
    private final StubbornLink slp;
    private final List<Host> membership;
    private final int myId;

    // ---- Control ----
    private final AtomicBoolean running = new AtomicBoolean(false);

    // ---- Message bookkeeping ----
    private final Set<String> delivered = ConcurrentHashMap.newKeySet();       // Deduplication
    private final Set<String> awaitingAck = ConcurrentHashMap.newKeySet();     // Messages still expecting ACK
    private final Map<String, byte[]> sentMessages = new ConcurrentHashMap<>(); // Store actual bytes for removal later

    // ---- Delivery callback ----
    private volatile DeliverHandler deliverHandler = (sender, seq, data) -> {};

    // ---- Constants for message types ----
    private static final byte TYPE_DATA = 1;
    private static final byte TYPE_ACK  = 2;

    public PerfectLinkImpl(int myId, List<Host> membership, StubbornLink slp) {
        this.myId = myId;
        this.membership = membership;
        this.slp = slp;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;

        // Start underlying stubborn link
        slp.start();

        // Register callback for when stubborn link delivers something
        slp.onDeliver(this::handleReceive);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        slp.stop();
    }

    /**
     * Called by upper layer to send a message to a specific destination.
     * Encodes the message, records it in bookkeeping sets, and sends via SLP.
     */
    @Override
    public void send(Host dest, byte[] data, int seq) {
        // Construct DATA message
        byte[] msg = encodeMessage(TYPE_DATA, myId, seq, data);

        // Track this message until ACKed
        String k = key(dest.getId(), seq);
        awaitingAck.add(k);
        sentMessages.put(k, msg);

        // Send via stubborn link (will retry until removed)
        slp.send(dest, msg);
    }

    /**
     * Register a callback that will be invoked exactly once per unique (sender,seq).
     */
    @Override
    public void onDeliver(DeliverHandler handler) {
        this.deliverHandler = handler;
    }

    /**
     * Handles all messages received from the stubborn link layer.
     */
    private void handleReceive(int senderId, byte[] raw) {
        ByteBuffer bb = ByteBuffer.wrap(raw);

        byte type = bb.get();
        int sender = bb.getInt();
        int seq = bb.getInt();

        byte[] payload = new byte[bb.remaining()];
        bb.get(payload);

        if (type == TYPE_DATA) {
            String k = key(sender, seq);

            // Deduplicate — deliver only once per (sender, seq)
            if (delivered.add(k)) {
                deliverHandler.deliver(sender, seq, payload);
            }

            // Always send ACK for DATA (even if duplicate)
            Host senderHost = membership.get(sender - 1);
            byte[] ack = encodeMessage(TYPE_ACK, myId, seq, new byte[0]);
            slp.send(senderHost, ack);

        } else if (type == TYPE_ACK) {
            // ACK received — stop retransmitting
            String k = key(sender, seq);
            awaitingAck.remove(k);

            byte[] msg = sentMessages.remove(k);
            if (msg != null) {
                Host senderHost = membership.get(sender - 1);
                slp.remove(senderHost, msg); // Stop retransmission in stubborn link
            }
        }
    }

    /**
     * Encodes a message into bytes:
     * [ type(1B) | sender(4B) | seq(4B) | payload... ]
     */
    private byte[] encodeMessage(byte type, int senderId, int seq, byte[] data) {
        ByteBuffer bb = ByteBuffer.allocate(1 + 4 + 4 + data.length);
        bb.put(type);
        bb.putInt(senderId);
        bb.putInt(seq);
        bb.put(data);
        return bb.array();
    }

    /**
     * Unique key for identifying a message by (processId, seqNr).
     */
    private String key(int id, int seq) {
        return id + ":" + seq;
    }
}

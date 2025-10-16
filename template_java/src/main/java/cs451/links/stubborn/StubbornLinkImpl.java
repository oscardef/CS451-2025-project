package cs451.links.stubborn;

import cs451.Host;
import cs451.links.fairloss.FairLossLink;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * StubbornLinkImpl — Implements the Stubborn Point-to-Point Links (SLP).
 *
 * ----------------------------------------------------------------------
 * The Stubborn Link adds reliability on top of Fair-Loss Links (FLP)
 * by *repeatedly retransmitting* all sent messages forever.
 *
 * Properties:
 *  - Ensures that if the network eventually stops losing packets,
 *    all messages will eventually be delivered.
 *  - May deliver duplicates (deduplication will be handled by PL).
 *
 * Reference (slides pseudocode):
 *
 * upon p2pSend(m, q):
 *     while true:
 *         trigger <flp2pSend, m, q>
 *         wait Δ
 *
 * upon <flp2pDeliver, q, m>:
 *     trigger <p2pDeliver, q, m>
 * ----------------------------------------------------------------------
 */
public class StubbornLinkImpl implements StubbornLink {

    // ---- Layering ----
    private final FairLossLink flp;             // Lower layer
    private final List<Host> membership;        // All known hosts

    // ---- Control ----
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread resendThread;                // Retransmission loop thread

    // ---- Delivery callback ----
    private volatile DeliverHandler deliverHandler = (src, data) -> {};

    // ---- Outgoing message queue ----
    // Stores messages that should be continuously retransmitted.
    private final Queue<SendEntry> pending = new ConcurrentLinkedQueue<>();

    // ---- Resend interval (ms) ----
    private static final int RESEND_INTERVAL_MS = 100; // Adjust if needed

    // ---- Helper structure for messages ----
    private static final class SendEntry {
        final Host dest;
        final byte[] data;
        SendEntry(Host dest, byte[] data) {
            this.dest = dest;
            this.data = data;
        }
    }

    public StubbornLinkImpl(FairLossLink flp, List<Host> membership) {
        this.flp = flp;
        this.membership = membership;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;

        // Start underlying FLP
        flp.start();

        // Register to receive messages from FLP and deliver them upward
        flp.onDeliver((senderId, data) -> deliverHandler.deliver(senderId, data));

        // Start retransmission thread
        resendThread = new Thread(this::resendLoop, "slp-resend");
        resendThread.setDaemon(true);
        resendThread.start();
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        flp.stop();
        try {
            if (resendThread != null) resendThread.join();
        } catch (InterruptedException ignored) {}
    }

    @Override
    public void send(Host dest, byte[] data) {
        // Add message to the retransmission queue
        pending.add(new SendEntry(dest, data));

        // Immediately send once
        flp.send(dest, data);
    }

    @Override
    public void onDeliver(DeliverHandler handler) {
        this.deliverHandler = handler;
    }

    /**
     * Periodically re-sends all messages in the pending queue.
     */
    private void resendLoop() {
        while (running.get()) {
            try {
                for (SendEntry e : pending) {
                    flp.send(e.dest, e.data);
                }
                Thread.sleep(RESEND_INTERVAL_MS);
            } catch (InterruptedException ignored) {
            }
        }
    }

    /**
     * Removes a message from the retransmission queue.
     * Called by upper layers (e.g., PerfectLink) once a message has been acknowledged.
     */
    public void remove(Host dest, byte[] data) {
        pending.removeIf(e -> e.dest.equals(dest) && Arrays.equals(e.data, data));
    }

}

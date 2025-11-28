package cs451.links.stubborn;

import cs451.Host;
import cs451.links.fairloss.FairLossLink;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * StubbornLinkImpl — ensures reliable message delivery by continuously
 * retransmitting all pending DATA messages until acknowledged by the PerfectLink.
 *
 * Improvements:
 *  - Messages remain in resendMap until PerfectLink confirms ACK.
 *  - Resend interval lowered for faster recovery.
 *  - ACKs can be sent unreliably via sendOnce() (not kept in resend loop).
 *  - Thread-safe, minimal allocations, no silent drops.
 */
public class StubbornLinkImpl implements StubbornLink {

    private final FairLossLink flp;
    private final List<Host> membership;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread resendThread;

    /** Callback to deliver messages upward. */
    private volatile DeliverHandler deliverHandler = (src, data) -> {};

    /** Pending messages: key = (destId << 32) | seq-like hash, value = SendEntry */
    private final Map<Long, SendEntry> resendMap = new ConcurrentHashMap<>();

    /** Retransmit every few ms for better reliability under loss. */
    private static final int RESEND_INTERVAL_MS = 20;

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

        flp.start();
        flp.onDeliver((senderId, data) -> deliverHandler.deliver(senderId, data));

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

    /**
     * Send a message that must be retransmitted until ACKed.
     */
    @Override
    public void send(Host dest, byte[] data) {
        // Compute stable key for deduping (hash avoids byte[] comparison)
        long key = (((long) dest.getId()) << 32) ^ Arrays.hashCode(data);
        resendMap.putIfAbsent(key, new SendEntry(dest, data));
        flp.send(dest, data); // immediate first send
    }

    /**
     * Send a message only once (for ACKs).
     */
    public void sendOnce(Host dest, byte[] data) {
        flp.send(dest, data);
    }

    /**
     * Remove message from resend map once an ACK is received.
     */
    public void remove(Host dest, byte[] data) {
        long key = (((long) dest.getId()) << 32) ^ Arrays.hashCode(data);
        resendMap.remove(key);
    }

    @Override
    public void onDeliver(DeliverHandler handler) {
        this.deliverHandler = handler;
    }

    /**
     * Periodically re-send all still-pending messages.
     */
    private void resendLoop() {
        while (running.get()) {
            try {
                for (SendEntry e : resendMap.values()) {
                    flp.send(e.dest, e.data);
                }
                Thread.sleep(RESEND_INTERVAL_MS);
            } catch (InterruptedException ignored) {
            } catch (Exception ex) {
                System.err.println("[StubbornLink] resend error: " + ex.getMessage());
            }
        }
    }
}

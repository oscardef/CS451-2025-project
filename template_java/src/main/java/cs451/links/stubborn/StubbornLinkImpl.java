package cs451.links.stubborn;

import cs451.Host;
import cs451.links.fairloss.FairLossLink;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * StubbornLinkImpl — keeps re-sending all messages periodically (classic SLP).
 * PL will call remove() once it gets an ACK.
 */
public class StubbornLinkImpl implements StubbornLink {

    private final FairLossLink flp;
    private final List<Host> membership;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread resendThread;

    private volatile DeliverHandler deliverHandler = (src, data) -> {};

    /** Messages to keep re-sending forever until removed. */
    private final Queue<SendEntry> pending = new ConcurrentLinkedQueue<>();

    /** Resend cadence (higher = fewer resends = less CPU, but slower recovery). */
    private static final int RESEND_INTERVAL_MS = 30;

    private static final class SendEntry {
        final Host dest;
        final byte[] data;
        SendEntry(Host dest, byte[] data) {
            this.dest = dest; this.data = data;
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
        try { if (resendThread != null) resendThread.join(); } catch (InterruptedException ignored) {}
    }

    @Override
    public void send(Host dest, byte[] data) {
        pending.add(new SendEntry(dest, data)); // remember for periodic resend
        flp.send(dest, data);                   // send once now
    }

    @Override
    public void onDeliver(DeliverHandler handler) {
        this.deliverHandler = handler;
    }

    /** Periodically re-send everything that hasn't been ACKed away. */
    private void resendLoop() {
        while (running.get()) {
            try {
                for (SendEntry e : pending) {
                    flp.send(e.dest, e.data);
                }
                Thread.sleep(RESEND_INTERVAL_MS);
            } catch (InterruptedException ignored) {}
        }
    }

    /** Called by PL when an ACK is received for (dest,msg). */
    public void remove(Host dest, byte[] data) {
        pending.removeIf(e -> e.dest.equals(dest) && Arrays.equals(e.data, data));
    }
}

package cs451.links.stubborn;

import cs451.Host;
import cs451.links.fairloss.FairLossLink;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stubborn Links implementation.
 * 
 * Retransmits pending messages every 20ms until removed by upper layer.
 */
public class StubbornLinkImpl implements StubbornLink {

    private final FairLossLink flp;
    private final List<Host> membership;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread resendThread;

    private volatile DeliverHandler deliverHandler = (src, data) -> {};

    private final Map<Long, SendEntry> resendMap = new ConcurrentHashMap<>();
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
        
        // Interrupt resend thread to avoid waiting for sleep to complete
        if (resendThread != null) {
            resendThread.interrupt();
        }
        
        flp.stop();
        
        try {
            if (resendThread != null) {
                resendThread.join(100); // Wait up to 100ms
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void send(Host dest, byte[] data) {
        long key = (((long) dest.getId()) << 32) ^ Arrays.hashCode(data);
        resendMap.putIfAbsent(key, new SendEntry(dest, data));
        flp.send(dest, data);
    }

    public void sendOnce(Host dest, byte[] data) {
        flp.send(dest, data);
    }

    public void remove(Host dest, byte[] data) {
        long key = (((long) dest.getId()) << 32) ^ Arrays.hashCode(data);
        resendMap.remove(key);
    }

    @Override
    public void onDeliver(DeliverHandler handler) {
        this.deliverHandler = handler;
    }

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

package cs451.links.fairloss;

import cs451.Host;

/**
 * Fair-Loss Point-to-Point Links interface.
 * 
 * Best-effort UDP transport. May lose, duplicate, or reorder messages.
 */
public interface FairLossLink {

    void start();
    void stop();
    void send(Host dest, byte[] data);
    void onDeliver(DeliverHandler handler);

    @FunctionalInterface
    interface DeliverHandler {
        void deliver(int sourceId, byte[] data);
    }
}

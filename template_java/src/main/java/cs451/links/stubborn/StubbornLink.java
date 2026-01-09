package cs451.links.stubborn;

import cs451.Host;
import cs451.links.fairloss.FairLossLink;

/**
 * Stubborn Point-to-Point Links interface.
 * 
 * Retransmits messages periodically until removed.
 * Built on top of Fair-Loss Links.
 */
public interface StubbornLink {

    void start();
    void stop();
    void send(Host dest, byte[] data);
    void onDeliver(DeliverHandler handler);
    void remove(Host dest, byte[] msg);

    @FunctionalInterface
    interface DeliverHandler {
        void deliver(int senderId, byte[] data);
    }
}

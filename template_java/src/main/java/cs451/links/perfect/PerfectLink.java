package cs451.links.perfect;

import cs451.Host;

/**
 * Perfect Point-to-Point Links interface.
 * 
 * Guarantees reliable delivery, no duplication, and no creation.
 * Built on top of Stubborn Links with ACK-based retransmission control.
 */
public interface PerfectLink {

    void start();
    void stop();
    void send(Host dest, byte[] data, int seq);
    void onDeliver(DeliverHandler handler);

    @FunctionalInterface
    interface DeliverHandler {
        void deliver(int senderId, int seq, byte[] data);
    }
}

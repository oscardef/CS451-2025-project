package cs451.links.stubborn;

import cs451.Host;
import cs451.links.fairloss.FairLossLink;

/**
 * Interface for Stubborn Point-to-Point Links (SLP).
 *
 * ---------------------------------------------------------------
 * Adds retransmission on top of Fair-Loss Links:
 * ---------------------------------------------------------------
 *  - Messages are repeatedly sent (every Δ milliseconds)
 *  - Ensures eventual delivery if the network eventually delivers
 *  - Still allows duplicates (no deduplication yet)
 *
 * Used by: PerfectLinkImpl (upper layer)
 * Built on top of: FairLossLink (lower layer)
 */
public interface StubbornLink {

    /**
     * Starts background retransmission threads.
     */
    void start();

    /**
     * Stops all background processing.
     */
    void stop();

    /**
     * Send a message to the destination process.
     * In practice, this means scheduling it for repeated retransmission
     * until {@link #stop()} is called.
     *
     * @param dest Destination host
     * @param data Serialized message bytes
     */
    void send(Host dest, byte[] data);

    /**
     * Register a callback to handle received messages.
     *
     * @param handler callback function (senderId, bytes)
     */
    void onDeliver(DeliverHandler handler);

    /**
     * Callback functional interface for deliveries.
     */
    @FunctionalInterface
    interface DeliverHandler {
        void deliver(int senderId, byte[] data);
    }

    /**
     * Removes a message from the retransmission queue.
     * Called by upper layers (e.g., PerfectLink) once a message has been acknowledged.
     *
     * @param senderHost The host that was the original sender of the message
     * @param msg The exact byte array of the message to be removed
     */
    void remove(Host senderHost, byte[] msg);
}

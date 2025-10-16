package cs451.links.perfect;

import cs451.Host;

/**
 * Interface for Perfect Point-to-Point Links (PL).
 *
 * ----------------------------------------------------------------------
 * Built on top of Stubborn Links (SLP).
 * ----------------------------------------------------------------------
 * Guarantees (as per slides):
 *  1. Reliable delivery — if a correct process p sends m to q,
 *     then q eventually delivers m.
 *  2. No duplication — m is delivered at most once.
 *  3. No creation — only messages that were actually sent are delivered.
 *
 * Underlying:
 *  - Uses StubbornLink (which resends messages infinitely)
 *  - Adds ACKs to stop retransmission once acknowledged
 *  - Adds deduplication on delivery side
 *
 * Used by: application-level broadcast or higher layers.
 */
public interface PerfectLink {

    /**
     * Starts background threads for message reception and retransmission.
     */
    void start();

    /**
     * Stops background processing and closes resources.
     */
    void stop();

    /**
     * Sends a message reliably to a given destination process.
     * The PL will ensure the message is eventually delivered and only once.
     *
     * @param dest Destination host
     * @param data Serialized message data
     * @param seq  Sequence number (for deduplication)
     */
    void send(Host dest, byte[] data, int seq);

    /**
     * Register a delivery handler for received messages.
     *
     * @param handler callback function (senderId, seq, bytes)
     */
    void onDeliver(DeliverHandler handler);

    /**
     * Callback for message deliveries.
     */
    @FunctionalInterface
    interface DeliverHandler {
        void deliver(int senderId, int seq, byte[] data);
    }
}

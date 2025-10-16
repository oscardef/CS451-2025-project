package cs451.links.fairloss;

import cs451.Host;

/**
 * Interface for Fair-Loss Point-to-Point Links (FLP).
 *
 * Follows the model from the course slides:
 * - Provides best-effort delivery over an unreliable network (UDP).
 * - May lose, duplicate, or reorder messages.
 * - Guarantees "no creation" — messages delivered were actually sent.
 *
 * Used as the lowest layer in the link hierarchy:
 * Application -> PerfectLink -> StubbornLink -> FairLossLink -> UDP network
 */
public interface FairLossLink {

    /**
     * Starts background threads for receiving incoming packets.
     */
    void start();

    /**
     * Stops all background processing and closes the underlying socket.
     */
    void stop();

    /**
     * Sends a message to the given destination host.
     *
     * @param dest Destination host
     * @param data Byte array representing the message contents
     */
    void send(Host dest, byte[] data);

    /**
     * Registers a delivery handler to be called when a message is received.
     *
     * @param handler A function accepting (sourceId, messageBytes)
     */
    void onDeliver(DeliverHandler handler);

    /**
     * Functional interface for delivery callbacks. A clean and short way to pass functions.
     */
    @FunctionalInterface
    interface DeliverHandler {
        void deliver(int sourceId, byte[] data);
    }
}

package cs451.pl;

/**
 * PerfectLink — abstraction for PL over UDP.
 * We expose start/stop + send(), and deliver events via a callback.
 */
public interface PerfectLink extends AutoCloseable {

    /** Start background networking (receiver + timers). Idempotent. */
    void start();

    /** Stop threads and release resources. Idempotent. */
    void stop();

    /**
     * Asynchronously send one application message with sequence number 'seq'
     * to destination 'destId'. The implementation handles reliability.
     */
    void send(int destId, int seq);

    /**
     * Register a delivery callback. Called exactly once per (sender, seq)
     * upon *first* delivery at the receiver.
     */
    void onDeliver(DeliverCallback callback);

    @Override
    default void close() { stop(); }
}

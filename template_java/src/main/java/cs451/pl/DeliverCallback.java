package cs451.pl;

/** Functional interface used by PerfectLink to notify the app (receiver). */
@FunctionalInterface
public interface DeliverCallback {
    void deliver(int senderId, int seq);
}

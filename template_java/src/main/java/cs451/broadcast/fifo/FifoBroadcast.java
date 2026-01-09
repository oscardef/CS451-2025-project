package cs451.broadcast.fifo;

import cs451.broadcast.Message;

/**
 * FIFO Broadcast interface.
 * 
 * Delivers messages from each sender in the order they were broadcast.
 * Built on top of Uniform Reliable Broadcast.
 */
public interface FifoBroadcast {
    
    void start();
    void stop();
    void broadcast(int seq);
    void onDeliver(DeliverHandler handler);
    
    @FunctionalInterface
    interface DeliverHandler {
        void deliver(int sender, int seq);
    }
}

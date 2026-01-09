package cs451.broadcast.urb;

import cs451.broadcast.Message;

/**
 * Uniform Reliable Broadcast interface.
 * 
 * If any process (correct or faulty) delivers a message, all correct processes
 * eventually deliver it. Built on top of Perfect Links.
 */
public interface UniformReliableBroadcast {
    
    void start();
    void stop();
    void broadcast(Message msg);
    void onDeliver(DeliverHandler handler);
    
    @FunctionalInterface
    interface DeliverHandler {
        void deliver(Message msg);
    }
}

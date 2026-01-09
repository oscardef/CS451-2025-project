package cs451.broadcast.fifo;

import cs451.broadcast.Message;
import cs451.broadcast.urb.UniformReliableBroadcast;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * FIFO Broadcast implementation on top of URB.
 * 
 * Buffers out-of-order messages and delivers them in sequence order per sender.
 */
public final class FifoBroadcastImpl implements FifoBroadcast {
    
    private final int myId;
    private final int numProcesses;
    private final UniformReliableBroadcast urb;
    
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    // Next expected sequence number from each sender (indexed by senderId - 1)
    private final int[] nextExpected;
    
    // Pending messages buffer: sender -> (seq -> Message)
    private final Map<Integer, Map<Integer, Message>> pending = new ConcurrentHashMap<>();
    
    // Lock objects for synchronizing delivery per sender
    private final Object[] senderLocks;
    
    private volatile DeliverHandler deliverHandler = (sender, seq) -> {};
    
    public FifoBroadcastImpl(int myId, int numProcesses, UniformReliableBroadcast urb) {
        this.myId = myId;
        this.numProcesses = numProcesses;
        this.urb = urb;
        
        // Initialize nextExpected[sender] = 1 for all senders
        this.nextExpected = new int[numProcesses];
        Arrays.fill(nextExpected, 1);
        
        // Initialize lock objects for each sender
        this.senderLocks = new Object[numProcesses];
        for (int i = 0; i < numProcesses; i++) {
            senderLocks[i] = new Object();
        }
        
        // Initialize pending buffers for all senders
        for (int sender = 1; sender <= numProcesses; sender++) {
            pending.put(sender, new ConcurrentHashMap<>());
        }
    }
    
    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        
        // Start URB layer
        urb.start();
        
        // Register URB delivery callback
        urb.onDeliver(this::handleUrbDeliver);
    }
    
    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        urb.stop();
    }
    
    @Override
    public void broadcast(int seq) {
        // Create message with our ID as origin
        Message msg = new Message(myId, seq);
        urb.broadcast(msg);
    }
    
    @Override
    public void onDeliver(DeliverHandler handler) {
        this.deliverHandler = handler;
    }
    
    private void handleUrbDeliver(Message msg) {
        int sender = msg.origin;
        int seq = msg.seq;
        
        Map<Integer, Message> senderPending = pending.get(sender);
        if (senderPending == null) {
            senderPending = new ConcurrentHashMap<>();
            pending.put(sender, senderPending);
        }
        
        int senderIdx = sender - 1;
        synchronized (senderLocks[senderIdx]) {
            senderPending.put(seq, msg);
            
            while (senderPending.containsKey(nextExpected[senderIdx])) {
                Message inOrderMsg = senderPending.remove(nextExpected[senderIdx]);
                deliverHandler.deliver(inOrderMsg.origin, inOrderMsg.seq);
                nextExpected[senderIdx]++;
            }
        }
    }
}

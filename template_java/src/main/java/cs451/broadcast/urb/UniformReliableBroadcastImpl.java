package cs451.broadcast.urb;

import cs451.Host;
import cs451.broadcast.Message;
import cs451.links.perfect.PerfectLink;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Flooding-based URB implementation.
 * 
 * Re-broadcasts messages on first receipt to ensure uniform agreement.
 */
public final class UniformReliableBroadcastImpl implements UniformReliableBroadcast {
    
    private final int myId;
    private final List<Host> membership;
    private final PerfectLink perfectLink;
    
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    // Track messages we've seen (to avoid re-broadcast storms)
    private final Set<Long> seen = ConcurrentHashMap.newKeySet();
    
    // Track messages we've delivered (to prevent duplication)
    private final Set<Long> delivered = ConcurrentHashMap.newKeySet();
    
    // Sequence counter for PerfectLink sends (per destination)
    // URB needs unique seq numbers for PL layer to avoid collisions
    private final Map<Integer, Integer> plSeqCounters = new ConcurrentHashMap<>();
    
    private volatile DeliverHandler deliverHandler = (msg) -> {};
    
    public UniformReliableBroadcastImpl(int myId, List<Host> membership, PerfectLink perfectLink) {
        this.myId = myId;
        this.membership = membership;
        this.perfectLink = perfectLink;
    }
    
    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) return;
        
        // Start Perfect Links layer
        perfectLink.start();
        
        // Register PL delivery callback to handle incoming URB messages
        perfectLink.onDeliver((senderId, seq, data) -> {
            // Decode message from PL payload
            Message msg = Message.decode(data);
            handleMessage(msg);
        });
    }
    
    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        perfectLink.stop();
    }
    
    @Override
    public void broadcast(Message msg) {
        // Treat our own broadcast as if we received it from ourselves
        handleMessage(msg);
    }
    
    @Override
    public void onDeliver(DeliverHandler handler) {
        this.deliverHandler = handler;
    }
    
    private void handleMessage(Message msg) {
        long key = msg.key();
        
        // Check if we've seen this message before
        if (seen.add(key)) {
            // First time seeing this message — re-broadcast to all others
            byte[] payload = msg.encode();
            
            for (Host dest : membership) {
                if (dest.getId() != myId) {
                    // Get next sequence number for this destination
                    // Each (myId, destId, plSeq) triple is unique in PerfectLinks
                    int plSeq = plSeqCounters.compute(dest.getId(), (k, v) -> (v == null) ? 1 : v + 1);
                    perfectLink.send(dest, payload, plSeq);
                }
            }
        }
        
        // Check if we've delivered this message before
        if (delivered.add(key)) {
            // First time delivering — notify upper layer (FRB)
            deliverHandler.deliver(msg);
        }
    }
}

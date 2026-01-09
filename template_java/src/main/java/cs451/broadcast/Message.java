package cs451.broadcast;

import java.nio.ByteBuffer;

/**
 * Broadcast message with origin and sequence number.
 * 
 * Wire format: [origin(4B) | seq(4B)]
 */
public final class Message {
    
    public final int origin;
    public final int seq;
    
    public Message(int origin, int seq) {
        this.origin = origin;
        this.seq = seq;
    }
    
    public byte[] encode() {
        ByteBuffer bb = ByteBuffer.allocate(8);
        bb.putInt(origin);
        bb.putInt(seq);
        return bb.array();
    }
    
    public static Message decode(byte[] data) {
        if (data == null || data.length < 8) {
            throw new IllegalArgumentException("Invalid message data");
        }
        ByteBuffer bb = ByteBuffer.wrap(data);
        return new Message(bb.getInt(), bb.getInt());
    }
    
    /** Unique key combining (origin, seq) for deduplication. */
    public long key() {
        return (((long) origin) << 32) | (seq & 0xffffffffL);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Message)) return false;
        Message m = (Message) obj;
        return origin == m.origin && seq == m.seq;
    }
    
    @Override
    public int hashCode() {
        return 31 * origin + seq;
    }
    
    @Override
    public String toString() {
        return String.format("Message{origin=%d, seq=%d}", origin, seq);
    }
}

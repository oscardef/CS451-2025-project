package cs451.pl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Message — fixed-size header used by Perfect Links.
 *
 * Wire format (big-endian, 9 bytes total):
 *   type:     1 byte  (1 = DATA, 2 = ACK)
 *   senderId: 2 bytes (unsigned short): short is a 16-bit signed two's complement integer. It has a minimum value of -32,768 and a maximum value of 32,767
 *   destId:   2 bytes (unsigned short)
 *   seq:      4 bytes (unsigned int)
 *
 * No payload for M1; the "message" is just (sender, seq). Keeping a header
 * makes it easy to extend later.
 */
public final class Message {
    public static final byte TYPE_DATA = 1;
    public static final byte TYPE_ACK  = 2;

    public byte type;
    public int senderId; // store as int [0..65535]
    public int destId;   // store as int [0..65535]
    public int seq;      // store as int [0..2^31-1] (fits our m)

    public Message(byte type, int senderId, int destId, int seq) {
        this.type = type;
        this.senderId = senderId;
        this.destId = destId;
        this.seq = seq;
    }

    /** Serialize into a caller-provided buffer (no allocation). Returns bytes written. */
    public int writeTo(ByteBuffer bb) {
        bb.order(ByteOrder.BIG_ENDIAN);
        bb.put(type);
        bb.putShort((short) senderId);
        bb.putShort((short) destId);
        bb.putInt(seq);
        return 1 + 2 + 2 + 4; // total bytes written
    }

    /** Deserialize from a buffer positioned at message start. */
    public static Message readFrom(ByteBuffer bb) {
        bb.order(ByteOrder.BIG_ENDIAN);
        byte t = bb.get();                              // type  
        int s  = Short.toUnsignedInt(bb.getShort());    // senderId
        int d  = Short.toUnsignedInt(bb.getShort());    // destId
        int q  = bb.getInt();                           // seq
        return new Message(t, s, d, q);
    }

    /** Number of bytes this header occupies on the wire. */
    public static int byteSize() { return 1 + 2 + 2 + 4; }
}

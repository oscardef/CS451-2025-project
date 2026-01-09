package cs451.lattice;

import java.nio.ByteBuffer;
import java.util.*;

/**
 * Message for the Lattice Agreement protocol.
 * 
 * Types: PROPOSAL, ACK, NACK
 * Wire format: [type(1B) | agreementId(4B) | proposerId(4B) | proposalNumber(4B) | setSize(4B) | values...]
 */
public final class LatticeMessage {

    public static final byte TYPE_PROPOSAL = 1;
    public static final byte TYPE_ACK = 2;
    public static final byte TYPE_NACK = 3;

    public final byte type;
    public final int agreementId;
    public final int proposerId;
    public final int proposalNumber;
    public final Set<Integer> values;

    public LatticeMessage(byte type, int agreementId, int proposerId, int proposalNumber, Set<Integer> values) {
        this.type = type;
        this.agreementId = agreementId;
        this.proposerId = proposerId;
        this.proposalNumber = proposalNumber;
        this.values = values != null ? values : Collections.emptySet();
    }

    public static LatticeMessage proposal(int agreementId, int proposerId, int proposalNumber, Set<Integer> values) {
        return new LatticeMessage(TYPE_PROPOSAL, agreementId, proposerId, proposalNumber, values);
    }

    public static LatticeMessage ack(int agreementId, int proposerId, int proposalNumber) {
        return new LatticeMessage(TYPE_ACK, agreementId, proposerId, proposalNumber, Collections.emptySet());
    }

    public static LatticeMessage nack(int agreementId, int proposerId, int proposalNumber, Set<Integer> acceptedValues) {
        return new LatticeMessage(TYPE_NACK, agreementId, proposerId, proposalNumber, acceptedValues);
    }

    public byte[] encode() {
        int size = 1 + 4 + 4 + 4 + 4 + (values.size() * 4);
        ByteBuffer bb = ByteBuffer.allocate(size);
        bb.put(type);
        bb.putInt(agreementId);
        bb.putInt(proposerId);
        bb.putInt(proposalNumber);
        bb.putInt(values.size());
        for (int v : values) {
            bb.putInt(v);
        }
        return bb.array();
    }

    public static LatticeMessage decode(byte[] data) {
        ByteBuffer bb = ByteBuffer.wrap(data);
        byte type = bb.get();
        int agreementId = bb.getInt();
        int proposerId = bb.getInt();
        int proposalNumber = bb.getInt();
        int setSize = bb.getInt();
        
        Set<Integer> values = new HashSet<>(setSize);
        for (int i = 0; i < setSize; i++) {
            values.add(bb.getInt());
        }
        
        return new LatticeMessage(type, agreementId, proposerId, proposalNumber, values);
    }

    public boolean isProposal() { return type == TYPE_PROPOSAL; }
    public boolean isAck() { return type == TYPE_ACK; }
    public boolean isNack() { return type == TYPE_NACK; }

    @Override
    public String toString() {
        String typeName = type == TYPE_PROPOSAL ? "PROPOSAL" : (type == TYPE_ACK ? "ACK" : "NACK");
        return String.format("%s[aid=%d, pid=%d, pnum=%d, vals=%s]", 
            typeName, agreementId, proposerId, proposalNumber, values);
    }
}

package cs451.lattice;

import cs451.Host;
import cs451.links.perfect.PerfectLink;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Lattice Agreement using the propose/ACK/NACK algorithm from the course.
 * 
 * Quorum size is f+1 where f = floor((n-1)/2).
 */
public final class LatticeAgreement {

    private final int myId;
    private final int numProcesses;
    private final List<Host> hosts;
    private final PerfectLink perfectLink;
    private final int quorum; // f + 1

    private final AtomicBoolean running = new AtomicBoolean(false);

    // Sequence counter for PL sends (per destination)
    private final Map<Integer, AtomicInteger> plSeqCounters = new ConcurrentHashMap<>();

    // Proposer state (per agreement)
    private volatile boolean[] proposerActive;
    private volatile Set<Integer>[] proposedValues;
    private volatile int[] activeProposalNumbers;
    private volatile int[] ackCounts;
    private volatile int[] nackCounts;
    private final Object[] proposerLocks;

    // Acceptor state (per agreement)
    private volatile Set<Integer>[] acceptedValues;

    // Decision tracking
    private volatile Set<Integer>[] decisions;
    private volatile boolean[] decided;
    private final Object[] decisionLocks;

    // Callback
    private volatile DecisionHandler decisionHandler = (agreementId, values) -> {};

    @FunctionalInterface
    public interface DecisionHandler {
        void onDecide(int agreementId, Set<Integer> values);
    }

    @SuppressWarnings("unchecked")
    public LatticeAgreement(int myId, List<Host> hosts, PerfectLink perfectLink, int numAgreements) {
        this.myId = myId;
        this.hosts = hosts;
        this.numProcesses = hosts.size();
        this.perfectLink = perfectLink;
        
        // Quorum: majority = floor((n-1)/2) + 1 = floor(n/2) + 1 for odd n, n/2 + 1 for even
        // Actually f = floor((n-1)/2), so f+1 = floor((n-1)/2) + 1
        // For n=3: f=1, quorum=2. For n=5: f=2, quorum=3.
        int f = (numProcesses - 1) / 2;
        this.quorum = f + 1;

        // Initialize per-agreement state
        proposerActive = new boolean[numAgreements];
        proposedValues = new Set[numAgreements];
        activeProposalNumbers = new int[numAgreements];
        ackCounts = new int[numAgreements];
        nackCounts = new int[numAgreements];
        proposerLocks = new Object[numAgreements];
        
        acceptedValues = new Set[numAgreements];
        
        decisions = new Set[numAgreements];
        decided = new boolean[numAgreements];
        decisionLocks = new Object[numAgreements];
        
        for (int i = 0; i < numAgreements; i++) {
            proposedValues[i] = new HashSet<>();
            proposerLocks[i] = new Object();
            acceptedValues[i] = new HashSet<>();  // Initially empty (⊥)
            decisionLocks[i] = new Object();
        }

        // Initialize PL sequence counters
        for (Host h : hosts) {
            plSeqCounters.put(h.getId(), new AtomicInteger(0));
        }
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        
        perfectLink.start();
        
        // Register callback for incoming messages
        perfectLink.onDeliver((senderId, seq, data) -> {
            try {
                LatticeMessage msg = LatticeMessage.decode(data);
                handleMessage(senderId, msg);
            } catch (Exception e) {
                // Ignore malformed messages
            }
        });
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        perfectLink.stop();
    }

    public void onDecide(DecisionHandler handler) {
        this.decisionHandler = handler;
    }

    /** Propose a value for a specific agreement instance. */
    public void propose(int agreementId, Set<Integer> proposal) {
        synchronized (proposerLocks[agreementId]) {
            proposedValues[agreementId] = new HashSet<>(proposal);
            proposerActive[agreementId] = true;
            activeProposalNumbers[agreementId]++;
            ackCounts[agreementId] = 0;
            nackCounts[agreementId] = 0;
        }
        
        // Line 13: BEB broadcast ⟨proposal, proposed_value_i, active_proposal_number_i⟩
        bebBroadcastProposal(agreementId);
    }

    /** Block until a decision is made for this agreement, then return it. */
    public Set<Integer> awaitDecision(int agreementId) throws InterruptedException {
        synchronized (decisionLocks[agreementId]) {
            while (!decided[agreementId]) {
                decisionLocks[agreementId].wait();
            }
            return decisions[agreementId];
        }
    }

    /**
     * Check if an agreement has decided.
     */
    public boolean hasDecided(int agreementId) {
        return decided[agreementId];
    }

    /**
     * Get the decision for an agreement (null if not yet decided).
     */
    public Set<Integer> getDecision(int agreementId) {
        return decisions[agreementId];
    }

    private void handleMessage(int senderId, LatticeMessage msg) {
        // Bounds check to prevent ArrayIndexOutOfBounds
        int aid = msg.agreementId;
        if (aid < 0 || aid >= proposerLocks.length) {
            return; // Ignore invalid agreement IDs
        }
        
        if (msg.isProposal()) {
            handleProposal(senderId, msg);
        } else if (msg.isAck()) {
            handleAck(senderId, msg);
        } else if (msg.isNack()) {
            handleNack(senderId, msg);
        }
    }

    /** Handle incoming PROPOSAL as acceptor. */
    private void handleProposal(int senderId, LatticeMessage msg) {
        int aid = msg.agreementId;
        int proposerId = msg.proposerId;
        int proposalNumber = msg.proposalNumber;
        Set<Integer> proposedValue = msg.values;

        // Bounds check for proposerId
        if (proposerId < 1 || proposerId > numProcesses) {
            return;
        }

        // Synchronized access to acceptor state for this agreement
        Set<Integer> responseValues = null;
        byte responseType;

        synchronized (proposerLocks[aid]) {  // Reuse lock for acceptor state too
            Set<Integer> myAccepted = acceptedValues[aid];
            
            // Check if accepted_value_i ⊆ proposed_value
            boolean isSubset = proposedValue.containsAll(myAccepted);
            
            if (isSubset) {
                // Lines 4-5: accepted_value_i ← proposed_value; send ACK
                acceptedValues[aid] = new HashSet<>(proposedValue);
                responseType = LatticeMessage.TYPE_ACK;
            } else {
                // Lines 7-8: accepted_value_i ← accepted_value_i ∪ proposed_value; send NACK
                Set<Integer> merged = new HashSet<>(myAccepted);
                merged.addAll(proposedValue);
                acceptedValues[aid] = merged;
                responseValues = new HashSet<>(merged);  // Send current accepted value
                responseType = LatticeMessage.TYPE_NACK;
            }
        }

        // Send response to the proposer
        Host proposerHost = hosts.get(proposerId - 1);
        LatticeMessage response;
        if (responseType == LatticeMessage.TYPE_ACK) {
            response = LatticeMessage.ack(aid, proposerId, proposalNumber);
        } else {
            response = LatticeMessage.nack(aid, proposerId, proposalNumber, responseValues);
        }
        sendToHost(proposerHost, response);
    }

    /** Handle incoming ACK as proposer. */
    private void handleAck(int senderId, LatticeMessage msg) {
        int aid = msg.agreementId;
        int proposalNumber = msg.proposalNumber;

        synchronized (proposerLocks[aid]) {
            if (proposalNumber != activeProposalNumbers[aid]) {
                return;
            }
            if (!proposerActive[aid]) {
                return;
            }
            
            ackCounts[aid]++;
            
            if (ackCounts[aid] >= quorum && proposerActive[aid]) {
                decide(aid, proposedValues[aid]);
            }
        }
    }

    /** Handle incoming NACK as proposer. */
    private void handleNack(int senderId, LatticeMessage msg) {
        int aid = msg.agreementId;
        int proposalNumber = msg.proposalNumber;
        Set<Integer> nackValue = msg.values;

        synchronized (proposerLocks[aid]) {
            if (proposalNumber != activeProposalNumbers[aid]) {
                return;
            }
            if (!proposerActive[aid]) {
                return;
            }
            
            proposedValues[aid].addAll(nackValue);
            nackCounts[aid]++;
            
            int totalResponses = ackCounts[aid] + nackCounts[aid];
            if (nackCounts[aid] > 0 && totalResponses >= quorum && proposerActive[aid]) {
                activeProposalNumbers[aid]++;
                ackCounts[aid] = 0;
                nackCounts[aid] = 0;
                bebBroadcastProposalUnlocked(aid);
            }
        }
    }

    private void decide(int agreementId, Set<Integer> value) {
        proposerActive[agreementId] = false;
        decided[agreementId] = true;
        decisions[agreementId] = new HashSet<>(value);
        
        synchronized (decisionLocks[agreementId]) {
            decisionLocks[agreementId].notifyAll();
        }
        
        decisionHandler.onDecide(agreementId, decisions[agreementId]);
    }

    /** Broadcast a proposal to all processes. */
    private void bebBroadcastProposal(int agreementId) {
        int proposalNumber;
        Set<Integer> values;
        
        synchronized (proposerLocks[agreementId]) {
            proposalNumber = activeProposalNumbers[agreementId];
            values = new HashSet<>(proposedValues[agreementId]);
        }
        
        LatticeMessage msg = LatticeMessage.proposal(agreementId, myId, proposalNumber, values);
        byte[] encoded = msg.encode();
        
        for (Host dest : hosts) {
            int seq = plSeqCounters.get(dest.getId()).incrementAndGet();
            perfectLink.send(dest, encoded, seq);
        }
    }

    /**
     * BEB broadcast a proposal (called from within proposerLocks[agreementId]).
     */
    private void bebBroadcastProposalUnlocked(int agreementId) {
        int proposalNumber = activeProposalNumbers[agreementId];
        Set<Integer> values = new HashSet<>(proposedValues[agreementId]);
        
        LatticeMessage msg = LatticeMessage.proposal(agreementId, myId, proposalNumber, values);
        byte[] encoded = msg.encode();
        
        for (Host dest : hosts) {
            int seq = plSeqCounters.get(dest.getId()).incrementAndGet();
            perfectLink.send(dest, encoded, seq);
        }
    }

    /**
     * Send a message to a specific host via Perfect Links.
     */
    private void sendToHost(Host dest, LatticeMessage msg) {
        int seq = plSeqCounters.get(dest.getId()).incrementAndGet();
        perfectLink.send(dest, msg.encode(), seq);
    }
}

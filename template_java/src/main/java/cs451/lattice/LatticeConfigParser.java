package cs451.lattice;

import java.io.*;
import java.util.*;

/**
 * Parser for Lattice Agreement config files.
 * 
 * Format: first line is "p vs d", followed by p lines of proposals.
 */
public final class LatticeConfigParser {

    private int numProposals;
    private int maxValuesPerProposal;
    private int distinctValues;
    private final List<Set<Integer>> proposals = new ArrayList<>();

    public void parse(String configPath) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(configPath))) {
            // Parse header line: p vs d
            String header = reader.readLine();
            if (header == null) {
                throw new IOException("Empty config file");
            }
            
            String[] parts = header.trim().split("\\s+");
            if (parts.length != 3) {
                throw new IOException("Invalid header format, expected: p vs d");
            }
            
            numProposals = Integer.parseInt(parts[0]);
            maxValuesPerProposal = Integer.parseInt(parts[1]);
            distinctValues = Integer.parseInt(parts[2]);
            
            // Parse proposal lines
            for (int i = 0; i < numProposals; i++) {
                String line = reader.readLine();
                if (line == null) {
                    throw new IOException("Expected " + numProposals + " proposals, found " + i);
                }
                
                Set<Integer> proposal = new HashSet<>();
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    for (String val : trimmed.split("\\s+")) {
                        proposal.add(Integer.parseInt(val));
                    }
                }
                proposals.add(proposal);
            }
        }
    }

    public int getNumProposals() {
        return numProposals;
    }

    public int getMaxValuesPerProposal() {
        return maxValuesPerProposal;
    }

    public int getDistinctValues() {
        return distinctValues;
    }

    public List<Set<Integer>> getProposals() {
        return proposals;
    }

    public Set<Integer> getProposal(int index) {
        return proposals.get(index);
    }
}

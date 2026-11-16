package org.example.simulation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Saves, loads, snapshots and restores the SARSA Q-table, so learning can continue from
 * one simulated day to the next and survive a restart. It only reads and writes the
 * Q-values; the learning rule itself is not touched.
 */
public final class QTableStore {

    private QTableStore() {}

    /** All Q-values in a fixed order (state-major). */
    public static double[] snapshot(SarsaAgent agent) {
        WorkloadState[] states = WorkloadState.values();
        ScalingAction[] actions = ScalingAction.values();
        double[] values = new double[states.length * actions.length];
        int k = 0;
        for (WorkloadState s : states) {
            for (ScalingAction a : actions) {
                values[k++] = agent.getQValue(s, a);
            }
        }
        return values;
    }

    public static void restore(SarsaAgent agent, double[] snapshot) {
        int k = 0;
        for (WorkloadState s : WorkloadState.values()) {
            for (ScalingAction a : ScalingAction.values()) {
                agent.getQTable().get(s).put(a, snapshot[k++]);
            }
        }
    }

    public static void save(SarsaAgent agent, Path file, int lastValidDay) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# lastValidDay=").append(lastValidDay).append('\n');
        sb.append("state,action,q_value\n");
        for (WorkloadState s : WorkloadState.values()) {
            for (ScalingAction a : ScalingAction.values()) {
                sb.append(s).append(',').append(a).append(',').append(agent.getQValue(s, a)).append('\n');
            }
        }
        BatchCheckpoint.writeAtomically(file, sb.toString());
    }

    /** @return the lastValidDay stored in the file */
    public static int load(SarsaAgent agent, Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        if (lines.isEmpty() || !lines.get(0).startsWith("# lastValidDay=")) {
            throw new IOException("Not a Q-table file: " + file);
        }
        int day = Integer.parseInt(lines.get(0).substring("# lastValidDay=".length()).trim());
        int loaded = 0;
        for (int i = 2; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            String[] cells = lines.get(i).split(",");
            WorkloadState s = WorkloadState.valueOf(cells[0].trim());
            ScalingAction a = ScalingAction.valueOf(cells[1].trim());
            agent.getQTable().get(s).put(a, Double.parseDouble(cells[2].trim()));
            loaded++;
        }
        if (loaded != WorkloadState.values().length * ScalingAction.values().length) {
            throw new IOException("Q-table file is incomplete (" + loaded + " values): " + file);
        }
        return day;
    }
}

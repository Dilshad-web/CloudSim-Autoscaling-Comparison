package org.example.simulation;

import java.util.Arrays;
import java.util.Random;
import java.util.List;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Q-Threshold Manager
 *
 * The Q-Threshold approach learns CPU utilization thresholds
 * instead of directly learning SCALE_IN / SCALE_OUT actions.
 *
 * State:
 *     Current number of VMs
 *
 * Actions:
 *     -2%, -1%, 0%, +1%, +2%
 *
 * Two independent Q-tables are maintained:
 *     1. Lower-threshold Q-table
 *     2. Upper-threshold Q-table
 *
 * The learned thresholds are then used to make the final
 * scaling decision.
 */
public class QThresholdManager {

    /*
     * Maximum number of VM states.
     *
     * VM count can range from 2 to 40 in this project.
     * We use MAX_VMS + 1 so that the VM count itself
     * can directly be used as the state index.
     */
    private static final int MAX_STATES =
            VmManager.MAX_VMS + 1;

    /*
     * Threshold adjustment actions.
     *
     * Example:
     *
     * Current lower threshold = 30%
     * Action = +2
     * New lower threshold = 32%
     */
    private static final int[] THRESHOLD_ACTIONS = {
            -2,
            -1,
            0,
            1,
            2
    };

    /*
     * Initial threshold values.
     *
     * These are starting values only.
     * Q-learning can adjust them during the simulation.
     */
    private double lowerThreshold = 30.0;
    private double upperThreshold = 70.0;

    /** Minimum dead-band between scale-in and scale-out thresholds. */
    private static final double MIN_THRESHOLD_GAP = 20.0;

    /*
     * Q-learning parameters.
     *
     * alpha    = learning rate
     * gamma    = discount factor
     * epsilon  = exploration probability
     */
    private final double alpha;
    private final double gamma;
    private double epsilon;

    /*
     * Random generator used for epsilon-greedy exploration.
     */
    private final Random random;

    /*
     * Q-table for the lower threshold.
     *
     * Rows    = VM-count states
     * Columns = threshold adjustment actions
     */
    private final double[][] lowerQTable;

    /*
     * Q-table for the upper threshold.
     */
    private final double[][] upperQTable;


    /**
     * Creates the Q-Threshold manager using default
     * Q-learning parameters.
     */
    public QThresholdManager() {

        this(
                0.1,       // learning rate
                0.9,       // discount factor
                0.1        // exploration probability
        );
    }


    /**
     * Creates the Q-Threshold manager.
     *
     * @param alpha learning rate
     * @param gamma discount factor
     * @param epsilon exploration probability
     */
    public QThresholdManager(
            double alpha,
            double gamma,
            double epsilon) {

        this.alpha = alpha;
        this.gamma = gamma;
        this.epsilon = epsilon;

        this.random =
                new Random(42);

        this.lowerQTable =
                new double[MAX_STATES][
                        THRESHOLD_ACTIONS.length
                        ];

        this.upperQTable =
                new double[MAX_STATES][
                        THRESHOLD_ACTIONS.length
                        ];
    }


    /**
     * Returns the current lower threshold.
     *
     * @return lower threshold in percentage
     */
    public double getLowerThreshold() {
        return lowerThreshold;
    }


    /**
     * Returns the current upper threshold.
     *
     * @return upper threshold in percentage
     */
    public double getUpperThreshold() {
        return upperThreshold;
    }


    /**
     * Sets the lower threshold.
     *
     * This method is mainly useful for experiments
     * and testing.
     */
    public void setLowerThreshold(
            double threshold) {

        lowerThreshold =
                clampLowerThreshold(threshold);
        maintainThresholdOrder();
    }


    /**
     * Sets the upper threshold.
     */
    public void setUpperThreshold(
            double threshold) {

        upperThreshold =
                clampUpperThreshold(threshold);
        maintainThresholdOrder();
    }


    /**
     * Returns the exploration probability.
     */
    public double getEpsilon() {
        return epsilon;
    }


    /**
     * Changes the exploration probability.
     */
    public void setEpsilon(
            double epsilon) {

        this.epsilon =
                Math.max(
                        0.0,
                        Math.min(
                                1.0,
                                epsilon
                        )
                );
    }


    /**
     * Determines the scaling action using the
     * currently learned thresholds.
     *
     * CPU utilization is expected as a percentage.
     *
     * Example:
     *
     * utilization = 20%
     * lower threshold = 30%
     *
     * Therefore:
     * 20 < 30
     * -> SCALE_IN
     *
     * utilization = 85%
     * upper threshold = 70%
     *
     * Therefore:
     * 85 > 70
     * -> SCALE_OUT
     *
     * Otherwise:
     * -> NO_ACTION
     *
     * @param cpuUtilization CPU utilization in percentage
     * @return scaling action
     */
    public ScalingAction decideScalingAction(
            double cpuUtilization) {

        /*
         * Make sure utilization stays between
         * 0% and 100%.
         */
        double utilization =
                Math.max(
                        0.0,
                        Math.min(
                                100.0,
                                cpuUtilization
                        )
                );

        /*
         * CPU below lower threshold:
         * reduce resources.
         */
        if (utilization < lowerThreshold) {
            return ScalingAction.SCALE_IN;
        }

        /*
         * CPU above upper threshold:
         * increase resources.
         */
        if (utilization > upperThreshold) {
            return ScalingAction.SCALE_OUT;
        }

        /*
         * CPU is between the two thresholds.
         */
        return ScalingAction.NO_ACTION;
    }


    /**
     * Selects an action for adjusting the lower threshold.
     *
     * Q-learning uses epsilon-greedy selection:
     *
     * With probability epsilon:
     *     explore a random action
     *
     * Otherwise:
     *     select the action with the highest Q-value.
     *
     * @param currentVmCount current number of VMs
     * @return threshold adjustment
     */
    public int chooseLowerThresholdAdjustment(
            int currentVmCount) {

        int state =
                normalizeState(currentVmCount);

        return THRESHOLD_ACTIONS[
                chooseAction(
                        lowerQTable[state]
                )
                ];
    }


    /**
     * Selects an action for adjusting the upper threshold.
     *
     * @param currentVmCount current number of VMs
     * @return threshold adjustment
     */
    public int chooseUpperThresholdAdjustment(
            int currentVmCount) {

        int state =
                normalizeState(currentVmCount);

        return THRESHOLD_ACTIONS[
                chooseAction(
                        upperQTable[state]
                )
                ];
    }


    /**
     * Applies an adjustment to the lower threshold.
     *
     * @param adjustment threshold change
     */
    public void adjustLowerThreshold(
            int adjustment) {

        lowerThreshold =
                clampLowerThreshold(
                        lowerThreshold + adjustment
                );
    }


    /**
     * Applies an adjustment to the upper threshold.
     *
     * @param adjustment threshold change
     */
    public void adjustUpperThreshold(
            int adjustment) {

        upperThreshold =
                clampUpperThreshold(
                        upperThreshold + adjustment
                );
    }


    /**
     * Updates the lower-threshold Q-table.
     *
     * Standard Q-learning update:
     *
     * Q(s,a) =
     * Q(s,a) +
     * alpha * [
     *     reward +
     *     gamma * max Q(s',a')
     *     -
     *     Q(s,a)
     * ]
     *
     * @param currentVmCount current VM count
     * @param adjustment selected threshold adjustment
     * @param reward reward received after the action
     * @param nextVmCount next VM count
     */
    public void updateLowerQTable(
            int currentVmCount,
            int adjustment,
            double reward,
            int nextVmCount) {

        int currentState =
                normalizeState(currentVmCount);

        int nextState =
                normalizeState(nextVmCount);

        int actionIndex =
                getActionIndex(adjustment);

        double currentQ =
                lowerQTable[
                        currentState
                        ][
                        actionIndex
                        ];

        double maxNextQ =
                getMaximumQValue(
                        lowerQTable[nextState]
                );

        double updatedQ =
                currentQ
                        + alpha
                        * (
                        reward
                                + gamma * maxNextQ
                                - currentQ
                );

        lowerQTable[
                currentState
                ][
                actionIndex
                ] = updatedQ;
    }


    /**
     * Updates the upper-threshold Q-table.
     *
     * @param currentVmCount current VM count
     * @param adjustment selected threshold adjustment
     * @param reward reward received after the action
     * @param nextVmCount next VM count
     */
    public void updateUpperQTable(
            int currentVmCount,
            int adjustment,
            double reward,
            int nextVmCount) {

        int currentState =
                normalizeState(currentVmCount);

        int nextState =
                normalizeState(nextVmCount);

        int actionIndex =
                getActionIndex(adjustment);

        double currentQ =
                upperQTable[
                        currentState
                        ][
                        actionIndex
                        ];

        double maxNextQ =
                getMaximumQValue(
                        upperQTable[nextState]
                );

        double updatedQ =
                currentQ
                        + alpha
                        * (
                        reward
                                + gamma * maxNextQ
                                - currentQ
                );

        upperQTable[
                currentState
                ][
                actionIndex
                ] = updatedQ;
    }


    /**
     * Performs one Q-Threshold learning step.
     *
     * The method:
     *
     * 1. Reads the current VM state.
     * 2. Selects lower-threshold action.
     * 3. Selects upper-threshold action.
     * 4. Adjusts the thresholds.
     * 5. Uses the learned thresholds to determine
     *    the scaling action.
     *
     * @param currentVmCount current VM count
     * @param cpuUtilization CPU utilization in percentage
     * @return scaling action
     */
    /**
     * Updates both Q-tables using the reward obtained
     * after a simulation run.
     */
    public void learnFromReward(
            int currentVmCount,
            int lowerAdjustment,
            int upperAdjustment,
            double reward,
            int nextVmCount) {

        updateLowerQTable(
                currentVmCount,
                lowerAdjustment,
                reward,
                nextVmCount
        );

        updateUpperQTable(
                currentVmCount,
                upperAdjustment,
                reward,
                nextVmCount
        );
    }



    public ScalingAction learnAndDecide(
            int currentVmCount,
            double cpuUtilization) {

        /*
         * Select threshold adjustment for
         * the lower threshold.
         */
        int lowerAdjustment =
                chooseLowerThresholdAdjustment(
                        currentVmCount
                );

        /*
         * Select threshold adjustment for
         * the upper threshold.
         */
        int upperAdjustment =
                chooseUpperThresholdAdjustment(
                        currentVmCount
                );

        /*
         * Apply the learned adjustments.
         */
        adjustLowerThreshold(
                lowerAdjustment
        );

        adjustUpperThreshold(
                upperAdjustment
        );

        /*
         * Make sure lower threshold always
         * remains below upper threshold.
         */
        maintainThresholdOrder();

        /*
         * Finally use the learned thresholds
         * to make the scaling decision.
         */
        return decideScalingAction(
                cpuUtilization
        );
    }


    /**
     * Returns a copy of the lower Q-table.
     *
     * Useful for debugging and experiments.
     */
    public double[][] getLowerQTable() {

        return copyTable(
                lowerQTable
        );
    }


    /**
     * Returns a copy of the upper Q-table.
     */
    public double[][] getUpperQTable() {

        return copyTable(
                upperQTable
        );
    }


    /**
     * Selects an action index using epsilon-greedy policy.
     */
    private int chooseAction(
            double[] qValues) {

        /*
         * Exploration.
         */
        if (random.nextDouble() < epsilon) {

            return random.nextInt(
                    THRESHOLD_ACTIONS.length
            );
        }

        /*
         * Exploitation:
         * choose the action with the
         * highest Q-value.
         */
        // Break ties randomly. In particular, all Q-values start at zero;
        // always selecting index zero would otherwise cause a systematic -2%
        // threshold drift before the learner has received any evidence.
        double bestValue = Arrays.stream(qValues).max().orElse(0.0);
        int[] tied = new int[qValues.length];
        int count = 0;
        for (int i = 0; i < qValues.length; i++) {
            if (Double.compare(qValues[i], bestValue) == 0) {
                tied[count++] = i;
            }
        }
        return tied[random.nextInt(count)];
    }


    /**
     * Returns the maximum Q-value from a row.
     */
    private double getMaximumQValue(
            double[] qValues) {

        double maximum =
                qValues[0];

        for (double value : qValues) {

            if (value > maximum) {
                maximum = value;
            }
        }

        return maximum;
    }


    /**
     * Converts a threshold adjustment into
     * its Q-table column index.
     */
    private int getActionIndex(
            int adjustment) {

        for (int i = 0;
             i < THRESHOLD_ACTIONS.length;
             i++) {

            if (THRESHOLD_ACTIONS[i]
                    == adjustment) {

                return i;
            }
        }

        throw new IllegalArgumentException(
                "Invalid threshold adjustment: "
                        + adjustment
        );
    }


    /**
     * Makes sure the VM count is a valid state.
     */
    private int normalizeState(
            int vmCount) {

        return Math.max(
                0,
                Math.min(
                        VmManager.MAX_VMS,
                        vmCount
                )
        );
    }


    /**
     * Keeps the lower threshold inside
     * a reasonable range.
     *
     * We keep it between 5% and 95%.
     */
    private double clampLowerThreshold(
            double threshold) {

        return Math.max(
                5.0,
                Math.min(
                        95.0,
                        threshold
                )
        );
    }


    /**
     * Keeps the upper threshold inside
     * a reasonable range.
     */
    private double clampUpperThreshold(
            double threshold) {

        return Math.max(
                5.0,
                Math.min(
                        95.0,
                        threshold
                )
        );
    }


    /**
     * Ensures:
     *
     * lower threshold < upper threshold
     */
    private void maintainThresholdOrder() {
        // Keep both thresholds in a range that leaves room for a stable dead-band.
        lowerThreshold = Math.max(5.0, Math.min(75.0, lowerThreshold));
        upperThreshold = Math.max(25.0, Math.min(95.0, upperThreshold));

        if (upperThreshold - lowerThreshold < MIN_THRESHOLD_GAP) {
            upperThreshold = lowerThreshold + MIN_THRESHOLD_GAP;
            if (upperThreshold > 95.0) {
                upperThreshold = 95.0;
                lowerThreshold = 95.0 - MIN_THRESHOLD_GAP;
            }
        }
    }


    /**
     * Creates a defensive copy of a Q-table.
     */
    private double[][] copyTable(
            double[][] original) {

        double[][] copy =
                new double[original.length][];

        for (int i = 0;
             i < original.length;
             i++) {

            copy[i] =
                    Arrays.copyOf(
                            original[i],
                            original[i].length
                    );

        }

        return copy;
    }
    public void adjustThresholds(
            int lowerAdjustment,
            int upperAdjustment) {

        adjustLowerThreshold(lowerAdjustment);
        adjustUpperThreshold(upperAdjustment);

        maintainThresholdOrder();
    }
    /** Set the deterministic random seed for a reproducible experiment run. */
    public void setRandomSeed(long seed) {
        random.setSeed(seed);
    }

    /** Deep snapshot used to roll back learning when a whole trace day is invalid. */
    public Snapshot snapshot() {
        return new Snapshot(lowerThreshold, upperThreshold, copyTable(lowerQTable), copyTable(upperQTable));
    }

    /** Restores thresholds and both Q-tables from a previous snapshot. */
    public void restore(Snapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("Snapshot must not be null");
        lowerThreshold = snapshot.lowerThreshold;
        upperThreshold = snapshot.upperThreshold;
        copyInto(snapshot.lowerQTable, lowerQTable);
        copyInto(snapshot.upperQTable, upperQTable);
    }

    public static final class Snapshot {
        private final double lowerThreshold;
        private final double upperThreshold;
        private final double[][] lowerQTable;
        private final double[][] upperQTable;

        private Snapshot(double lowerThreshold, double upperThreshold, double[][] lowerQTable, double[][] upperQTable) {
            this.lowerThreshold = lowerThreshold;
            this.upperThreshold = upperThreshold;
            this.lowerQTable = lowerQTable;
            this.upperQTable = upperQTable;
        }
    }

    private static void copyInto(double[][] source, double[][] destination) {
        if (source.length != destination.length) throw new IllegalArgumentException("Q-table row mismatch");
        for (int i = 0; i < source.length; i++) {
            if (source[i].length != destination[i].length) throw new IllegalArgumentException("Q-table column mismatch");
            System.arraycopy(source[i], 0, destination[i], 0, source[i].length);
        }
    }

    /** Saves thresholds and both Q-tables so a multi-day experiment can resume. */
    public void saveState(Path file) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("thresholds,").append(lowerThreshold).append(',').append(upperThreshold).append('\n');
        for (int i = 0; i < lowerQTable.length; i++) {
            b.append("lower,").append(i);
            for (double q : lowerQTable[i]) b.append(',').append(q);
            b.append('\n');
        }
        for (int i = 0; i < upperQTable.length; i++) {
            b.append("upper,").append(i);
            for (double q : upperQTable[i]) b.append(',').append(q);
            b.append('\n');
        }
        Files.writeString(file, b.toString(), StandardCharsets.UTF_8);
    }

    /** Loads a previously saved controller state, validating table dimensions. */
    public void loadState(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.size() != 1 + lowerQTable.length + upperQTable.length || !lines.get(0).startsWith("thresholds,")) {
            throw new IOException("Invalid Q-Threshold state file: " + file);
        }
        String[] t = lines.get(0).split(",");
        lowerThreshold = Double.parseDouble(t[1]);
        upperThreshold = Double.parseDouble(t[2]);
        maintainThresholdOrder();
        for (int i = 1; i < lines.size(); i++) {
            String[] c = lines.get(i).split(",");
            boolean lower = c[0].equals("lower");
            if (!lower && !c[0].equals("upper")) throw new IOException("Invalid table label in " + file);
            int row = Integer.parseInt(c[1]);
            double[][] table = lower ? lowerQTable : upperQTable;
            if (row < 0 || row >= table.length || c.length != table[row].length + 2) {
                throw new IOException("Invalid Q-table row in " + file);
            }
            for (int col = 0; col < table[row].length; col++) {
                double q = Double.parseDouble(c[col + 2]);
                if (!Double.isFinite(q)) throw new IOException("Non-finite Q value in " + file);
                table[row][col] = q;
            }
        }
    }

}
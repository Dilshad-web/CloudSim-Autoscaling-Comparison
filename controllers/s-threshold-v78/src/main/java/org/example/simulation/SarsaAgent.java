
package org.example.simulation;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/**
 * SARSA reinforcement-learning agent used by the
 * S-Threshold horizontal auto-scaling algorithm.
 *
 * SARSA update:
 *
 * Q(s,a) = Q(s,a) +
 *          alpha * [reward + gamma * Q(s',a') - Q(s,a)]
 */
public class SarsaAgent {

    private final double alpha;
    private final double gamma;
    private final double epsilon;

    private final Random random;

    private final Map<WorkloadState, Map<ScalingAction, Double>> qTable;

    /**
     * Creates a SARSA agent.
     *
     * @param alpha learning rate
     * @param gamma discount factor
     * @param epsilon exploration rate
     */
    public SarsaAgent(
            double alpha,
            double gamma,
            double epsilon) {

        if (alpha < 0 || alpha > 1) {
            throw new IllegalArgumentException(
                    "Alpha must be between 0 and 1."
            );
        }

        if (gamma < 0 || gamma > 1) {
            throw new IllegalArgumentException(
                    "Gamma must be between 0 and 1."
            );
        }

        if (epsilon < 0 || epsilon > 1) {
            throw new IllegalArgumentException(
                    "Epsilon must be between 0 and 1."
            );
        }

        this.alpha = alpha;
        this.gamma = gamma;
        this.epsilon = epsilon;

        this.random = new Random();

        this.qTable =
                new EnumMap<>(WorkloadState.class);

        initializeQTable();
    }

    /**
     * Initializes Q(s,a) values to zero.
     */
    private void initializeQTable() {

        for (WorkloadState state : WorkloadState.values()) {

            Map<ScalingAction, Double> actions =
                    new EnumMap<>(ScalingAction.class);

            for (ScalingAction action :
                    ScalingAction.values()) {

                actions.put(action, 0.0);
            }

            qTable.put(state, actions);
        }
    }

    /**
     * Selects an action using epsilon-greedy policy.
     *
     * With probability epsilon:
     *     choose a random action.
     *
     * Otherwise:
     *     choose the action having the highest Q-value.
     */
    public ScalingAction chooseAction(
            WorkloadState state) {

        if (state == null) {
            throw new IllegalArgumentException(
                    "State cannot be null."
            );
        }

        // Exploration
        if (random.nextDouble() < epsilon) {

            ScalingAction[] actions =
                    ScalingAction.values();

            return actions[
                    random.nextInt(actions.length)
                    ];
        }

        // Exploitation
        return getBestAction(state);
    }

    /**
     * Returns the action with the highest Q-value.
     */
    private ScalingAction getBestAction(
            WorkloadState state) {

        Map<ScalingAction, Double> actions =
                qTable.get(state);

        ScalingAction bestAction =
                defaultActionForState(state);

        double bestValue =
                actions.get(bestAction);

        for (ScalingAction action :
                ScalingAction.values()) {

            double value =
                    actions.get(action);

            if (value > bestValue) {
                bestValue = value;
                bestAction = action;
            }
        }

        return bestAction;
    }

    /**
     * Breaks the initial zero-Q-value tie using the threshold policy.
     * This keeps the first decisions safe and lets SARSA refine them
     * after rewards are observed.
     */
    private ScalingAction defaultActionForState(WorkloadState state) {
        return switch (state) {
            case LOW -> ScalingAction.SCALE_IN;
            case HIGH -> ScalingAction.SCALE_OUT;
            case MEDIUM -> ScalingAction.NO_ACTION;
        };
    }

    /**
     * Performs the SARSA Q-value update.
     *
     * Q(s,a) <- Q(s,a) +
     * alpha * [r + gamma * Q(s',a') - Q(s,a)]
     */
    public void updateQValue(
            WorkloadState currentState,
            ScalingAction currentAction,
            double reward,
            WorkloadState nextState,
            ScalingAction nextAction) {

        if (currentState == null ||
                currentAction == null ||
                nextState == null ||
                nextAction == null) {

            throw new IllegalArgumentException(
                    "State and action values cannot be null."
            );
        }

        double currentQ =
                getQValue(
                        currentState,
                        currentAction
                );

        double nextQ =
                getQValue(
                        nextState,
                        nextAction
                );

        double target =
                reward + gamma * nextQ;

        double updatedQ =
                currentQ +
                        alpha * (target - currentQ);

        qTable
                .get(currentState)
                .put(
                        currentAction,
                        updatedQ
                );
    }

    /**
     * Performs a terminal SARSA update. There is no next state/action at
     * the end of an episode, so the future-value term is zero.
     */
    public void updateTerminalQValue(
            WorkloadState currentState,
            ScalingAction currentAction,
            double reward) {

        if (currentState == null || currentAction == null) {
            throw new IllegalArgumentException(
                    "State and action values cannot be null."
            );
        }

        double currentQ = getQValue(currentState, currentAction);
        double updatedQ = currentQ + alpha * (reward - currentQ);

        qTable.get(currentState).put(currentAction, updatedQ);
    }

    /**
     * Returns Q(s,a).
     */
    public double getQValue(
            WorkloadState state,
            ScalingAction action) {

        if (state == null || action == null) {
            throw new IllegalArgumentException(
                    "State and action cannot be null."
            );
        }

        return qTable
                .get(state)
                .get(action);
    }

    /**
     * Returns the entire Q-table.
     */
    public Map<WorkloadState, Map<ScalingAction, Double>>
    getQTable() {

        return qTable;
    }

    public double getAlpha() {
        return alpha;
    }

    public double getGamma() {
        return gamma;
    }

    public double getEpsilon() {
        return epsilon;
    }

    /**
     * Prints the current Q-table.
     */
    public void printQTable() {

        System.out.println();
        System.out.println("========== SARSA Q-TABLE ==========");

        for (WorkloadState state :
                WorkloadState.values()) {

            System.out.println(
                    "State: " + state
            );

            for (ScalingAction action :
                    ScalingAction.values()) {

                System.out.println(
                        "  "
                                + action
                                + " = "
                                + getQValue(state, action)
                );
            }
        }

        System.out.println(
                "==================================="
        );
    }
}
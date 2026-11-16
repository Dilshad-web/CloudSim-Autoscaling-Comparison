package org.example.simulation;

/**
 * Implements the S-Threshold horizontal auto-scaling
 * decision logic combined with a SARSA agent.
 *
 * Threshold policy:
 *
 * CPU < 30%       -> SCALE_IN
 * CPU 30% - 70%   -> NO_ACTION
 * CPU > 70%       -> SCALE_OUT
 */
public class SThresholdManager {

    private static final double LOW_THRESHOLD = 30.0;
    private static final double HIGH_THRESHOLD = 70.0;

    private final SarsaAgent sarsaAgent;

    public SThresholdManager(
            SarsaAgent sarsaAgent) {

        if (sarsaAgent == null) {
            throw new IllegalArgumentException(
                    "SARSA agent cannot be null."
            );
        }

        this.sarsaAgent = sarsaAgent;
    }

    /**
     * Determines the threshold-based action
     * from CPU utilization.
     */
    public ScalingAction getThresholdAction(
            double cpuUtilization) {

        if (cpuUtilization < LOW_THRESHOLD) {

            return ScalingAction.SCALE_IN;

        } else if (cpuUtilization > HIGH_THRESHOLD) {

            return ScalingAction.SCALE_OUT;

        } else {

            return ScalingAction.NO_ACTION;
        }
    }

    /**
     * Determines the workload state.
     */
    public WorkloadState getWorkloadState(
            double cpuUtilization) {

        if (cpuUtilization < LOW_THRESHOLD) {

            return WorkloadState.LOW;

        } else if (cpuUtilization > HIGH_THRESHOLD) {

            return WorkloadState.HIGH;

        } else {

            return WorkloadState.MEDIUM;
        }
    }

    /**
     * Gets the SARSA-selected action for a workload state.
     */
    public ScalingAction getSarsaAction(
            WorkloadState state) {

        return sarsaAgent.chooseAction(state);
    }

    /**
     * Final S-Threshold + SARSA decision.
     *
     * The threshold determines whether scaling is required.
     * SARSA confirms the corresponding scaling action.
     *
     * LOW:
     *     SCALE_IN only when SARSA selects SCALE_IN.
     *
     * HIGH:
     *     SCALE_OUT only when SARSA selects SCALE_OUT.
     *
     * MEDIUM:
     *     NO_ACTION.
     */
    public ScalingAction decideFinalAction(
            double cpuUtilization) {

        WorkloadState state =
                getWorkloadState(cpuUtilization);

        ScalingAction sarsaAction =
                getSarsaAction(state);

        return decideFinalAction(cpuUtilization, sarsaAction);
    }

    /**
     * Combines the threshold rule with an already selected SARSA action.
     */
    public ScalingAction decideFinalAction(
            double cpuUtilization,
            ScalingAction sarsaAction) {

        ScalingAction thresholdAction =
                getThresholdAction(cpuUtilization);

        /*
         * Medium workload does not require scaling.
         */
        if (thresholdAction ==
                ScalingAction.NO_ACTION) {

            return ScalingAction.NO_ACTION;
        }

        /*
         * High workload:
         * scale out only if SARSA confirms it.
         */
        if (thresholdAction ==
                ScalingAction.SCALE_OUT) {

            if (sarsaAction ==
                    ScalingAction.SCALE_OUT) {

                return ScalingAction.SCALE_OUT;
            }

            return ScalingAction.NO_ACTION;
        }

        /*
         * Low workload:
         * scale in only if SARSA confirms it.
         */
        if (thresholdAction ==
                ScalingAction.SCALE_IN) {

            if (sarsaAction ==
                    ScalingAction.SCALE_IN) {

                return ScalingAction.SCALE_IN;
            }

            return ScalingAction.NO_ACTION;
        }

        return ScalingAction.NO_ACTION;
    }

    public SarsaAgent getSarsaAgent() {
        return sarsaAgent;
    }

    public double getLowThreshold() {
        return LOW_THRESHOLD;
    }

    public double getHighThreshold() {
        return HIGH_THRESHOLD;
    }
}
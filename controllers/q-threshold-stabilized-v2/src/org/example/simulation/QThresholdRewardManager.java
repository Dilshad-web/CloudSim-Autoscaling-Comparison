package org.example.simulation;

/**
 * Calculates the reward used by the Q-Threshold Q-learning algorithm.
 *
 * This class keeps the reward calculation separate from
 * QThresholdManager so that the reward policy can be changed
 * without changing the Q-learning implementation.
 *
 * NOTE:
 * This is a project-level configurable reward design.
 * It is NOT claimed to be the exact reward formula from
 * the original Q-Threshold research implementation.
 */
public class QThresholdRewardManager {

    /*
     * Response-time SLA target.
     *
     * If the average response time is at or below this
     * value, the SLA is considered satisfied.
     *
     * The value can be changed later according to the
     * experiment configuration.
     */
    private final double responseTimeSla;

    /*
     * Penalty applied when the response-time SLA is violated.
     */
    private final double slaViolationPenalty;

    /*
     * Reward for satisfying the response-time SLA.
     */
    private final double slaSuccessReward;


    /**
     * Creates a reward manager with default values.
     */
    public QThresholdRewardManager() {

        this(
                10.0,   // response-time SLA
                -1.0,   // SLA violation penalty
                1.0     // SLA success reward
        );
    }


    /**
     * Creates a reward manager with custom parameters.
     *
     * @param responseTimeSla maximum acceptable response time
     * @param slaViolationPenalty reward when SLA is violated
     * @param slaSuccessReward reward when SLA is satisfied
     */
    public QThresholdRewardManager(
            double responseTimeSla,
            double slaViolationPenalty,
            double slaSuccessReward) {

        if (responseTimeSla <= 0) {
            throw new IllegalArgumentException(
                    "Response-time SLA must be greater than 0."
            );
        }

        this.responseTimeSla =
                responseTimeSla;

        this.slaViolationPenalty =
                slaViolationPenalty;

        this.slaSuccessReward =
                slaSuccessReward;
    }


    /**
     * Calculates the reward using average response time.
     *
     * If response time satisfies the SLA:
     *
     *      reward = positive reward
     *
     * If response time violates the SLA:
     *
     *      reward = negative penalty
     *
     * @param averageResponseTime average response time
     * @return reward
     */
    public double calculateReward(
            double averageResponseTime) {

        /*
         * Invalid response time should not produce
         * a misleading positive reward.
         */
        if (averageResponseTime < 0) {
            return slaViolationPenalty;
        }

        /*
         * SLA satisfied.
         */
        if (averageResponseTime <= responseTimeSla) {

            /*
             * Give a higher reward when response time
             * is comfortably below the SLA.
             */
            double performanceRatio =
                    (responseTimeSla - averageResponseTime)
                            / responseTimeSla;

            return slaSuccessReward
                    + performanceRatio;
        }

        /*
         * SLA violated.
         *
         * Calculate how much the response time
         * exceeded the SLA.
         */
        double violationRatio =
                (averageResponseTime - responseTimeSla)
                        / responseTimeSla;

        /*
         * Limit the penalty so that extremely large
         * response times do not completely dominate
         * Q-values.
         */
        double boundedViolation =
                Math.min(
                        1.0,
                        violationRatio
                );

        return slaViolationPenalty
                * (1.0 + boundedViolation);
    }


    /**
     * Calculates reward while also considering
     * resource usage.
     *
     * This method can be used later when we want the
     * autoscaler to balance SLA performance and the
     * number of VMs being used.
     *
     * @param averageResponseTime average response time
     * @param currentVmCount current number of VMs
     * @param maxVmCount maximum possible VM count
     * @return calculated reward
     */
    public double calculateReward(
            double averageResponseTime,
            int currentVmCount,
            int maxVmCount) {

        /*
         * Start with the response-time reward.
         */
        double responseReward =
                calculateReward(
                        averageResponseTime
                );

        /*
         * Avoid division by zero.
         */
        if (maxVmCount <= 0) {
            return responseReward;
        }

        /*
         * Keep VM count within a valid range.
         */
        int boundedVmCount =
                Math.max(
                        0,
                        Math.min(
                                currentVmCount,
                                maxVmCount
                        )
                );

        /*
         * Resource utilization ratio.
         *
         * More VMs means more resource usage.
         */
        double resourceRatio =
                (double) boundedVmCount
                        / maxVmCount;

        /*
         * Small resource penalty.
         *
         * This prevents the learning process from
         * unnecessarily preferring a very large number
         * of VMs when SLA performance is similar.
         */
        double resourcePenalty =
                0.1 * resourceRatio;

        return responseReward
                - resourcePenalty;
    }


    /**
     * Returns the configured response-time SLA.
     */
    public double getResponseTimeSla() {
        return responseTimeSla;
    }


    /**
     * Returns the configured SLA violation penalty.
     */
    public double getSlaViolationPenalty() {
        return slaViolationPenalty;
    }


    /**
     * Returns the configured SLA success reward.
     */
    public double getSlaSuccessReward() {
        return slaSuccessReward;
    }
}
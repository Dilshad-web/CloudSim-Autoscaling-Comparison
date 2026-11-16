package org.example.simulation;

import org.cloudbus.cloudsim.cloudlets.Cloudlet;

import java.util.List;

/** Calculates the five performance indices used by the paper. */
public final class SimulationMetrics {

    /**
     * true  = response, waiting and turnaround time are measured from each cloudlet's own
     *         arrival (its submission delay). Correct when cloudlets arrive over time.
     * false = measured from simulation start (t = 0). Only equal to the above when ALL
     *         cloudlets arrive at t = 0. With staggered arrivals it adds the arrival
     *         offset (about half the arrival window on average) to every cloudlet.
     * Makespan and throughput (paper Eq. 12) do not depend on this switch.
     */
    private static final boolean MEASURE_FROM_ARRIVAL = true;

    private SimulationMetrics() {}

    public static Metrics calculate(List<Cloudlet> cloudlets) {
        if (cloudlets == null || cloudlets.isEmpty()) {
            return new Metrics(0, 0, 0, 0, 0, 0);
        }

        double totalResponse = 0;
        double totalWaiting = 0;
        double totalTurnaround = 0;
        double maxFinish = 0;
        double sumFinish = 0;
        int completed = 0;

        for (Cloudlet cloudlet : cloudlets) {
            completed++;
            // Cloudlets are explicitly submitted with zero delay in CloudletManager.
            // Using the measured execution start time keeps waiting time tied to
            // the actual CloudSim schedule instead of a hardcoded value.
            double submission = MEASURE_FROM_ARRIVAL ? cloudlet.getSubmissionDelay() : 0.0;
            double finish = cloudlet.getFinishTime();
            double start = cloudlet.getExecStartTime();

            if (!Double.isFinite(finish) || !Double.isFinite(start) || finish > 1_000_000) {
                throw new IllegalStateException(
                        "Invalid CloudSim timing detected for Cloudlet " + cloudlet.getId()
                                + ": finish=" + finish + ", start=" + start
                );
            }

            // NOTE: with all cloudlets submitted at t=0 (no staggered arrivals),
            // this intentionally equals averageResponseTime below. The paper's
            // own Section 4.3.2 text defines response time as "both the
            // execution time and waiting time" -- i.e. total sojourn time,
            // the same definition it uses for turnaround time. So these two
            // metrics being numerically identical here is faithful to the
            // paper's stated definitions, not a computation bug.
            totalResponse += finish - submission;
            totalWaiting += start - submission;
            totalTurnaround += finish - submission;
            maxFinish = Math.max(maxFinish, finish);
            sumFinish += finish;
        }

        if (completed == 0) {
            return new Metrics(0, 0, 0, 0, 0, 0);
        }

        double makespan = maxFinish;
        double averageResponse = totalResponse / completed;
        double averageWaiting = totalWaiting / completed;
        double averageTurnaround = totalTurnaround / completed;

        // Paper Eq. (12): throughput = N / sum(FTime).
        double throughput = sumFinish > 0 ? completed / sumFinish : 0;

        return new Metrics(
                makespan,
                averageResponse,
                averageWaiting,
                averageTurnaround,
                throughput,
                completed
        );
    }

    public record Metrics(
            double makespan,
            double averageResponseTime,
            double averageWaitingTime,
            double averageTurnaroundTime,
            double throughput,
            int completedCloudlets) {}
}
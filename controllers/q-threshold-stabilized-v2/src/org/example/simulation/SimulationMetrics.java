package org.example.simulation;

import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.datacenters.Datacenter;

import java.util.List;

/**
 * Shared metric definitions for the trace-driven comparison.
 * These definitions match the S-Threshold trace runner's common metrics.
 */
public final class SimulationMetrics {
    private SimulationMetrics() {}

    public record Metrics(
            double makespan,
            double averageResponseTime,
            double averageWaitingTime,
            double averageTurnaroundTime,
            double throughput) {}

    /**
     * Response = finish - arrival; waiting = execution start - arrival;
     * turnaround = finish - arrival; makespan = max finish time (trace starts at t=0);
     * throughput = completed cloudlets / makespan.
     *
     * The datacenter argument is retained for compatibility with the older API.
     * Arrival is taken from the cloudlet's submission delay, which is how the
     * trace runner creates its staggered arrivals.
     */
    public static Metrics calculate(List<Cloudlet> finishedCloudlets, Datacenter datacenter) {
        if (finishedCloudlets == null || finishedCloudlets.isEmpty()) {
            return new Metrics(0, 0, 0, 0, 0);
        }
        double response = 0, waiting = 0, turnaround = 0, makespan = 0;
        int count = 0;
        for (Cloudlet c : finishedCloudlets) {
            double arrival = c.getSubmissionDelay();
            double start = c.getExecStartTime();
            double finish = c.getFinishTime();
            if (!Double.isFinite(arrival) || !Double.isFinite(start) || !Double.isFinite(finish)
                    || arrival < 0 || start < arrival || finish < start) {
                throw new IllegalArgumentException("Invalid timestamps for cloudlet " + c.getId());
            }
            response += finish - arrival;
            waiting += start - arrival;
            turnaround += finish - arrival;
            makespan = Math.max(makespan, finish);
            count++;
        }
        if (count == 0 || makespan <= 0) return new Metrics(0, 0, 0, 0, 0);
        return new Metrics(makespan, response / count, waiting / count, turnaround / count, count / makespan);
    }
}

package org.example.simulation;

/** Result of ONE simulation run (one mode, one repetition) on one simulated day. */
public record RunRecord(
        String mode,
        int repetition,
        int cloudlets,
        int completed,
        int lostOnDestroy,
        double makespan,
        double avgResponse,
        double avgWaiting,
        double p95Response,
        double throughput,
        double avgVms,
        double vmSeconds,
        int scaleOut,
        int scaleIn,
        int flaps) {

    /** A run is usable only if every cloudlet finished and none was lost when a VM was removed. */
    public boolean isComplete() {
        return cloudlets > 0 && completed == cloudlets && lostOnDestroy == 0;
    }
}

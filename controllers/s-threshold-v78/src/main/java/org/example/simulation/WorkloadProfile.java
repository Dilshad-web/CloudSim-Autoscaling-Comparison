package org.example.simulation;

/**
 * A deterministic workload profile used to exercise the autoscaler.
 * The paper evaluates changing workload levels; this profile provides
 * a repeatable LOW -> MEDIUM -> HIGH -> MEDIUM -> LOW cycle.
 */
public enum WorkloadProfile {
    LOW(1, 1000, 0.20),
    MEDIUM(3, 1000, 0.50),
    HIGH(12, 1000, 0.90);

    private final int cloudletCount;
    private final long cloudletLength;
    private final double cpuUtilization;

    WorkloadProfile(int cloudletCount, long cloudletLength, double cpuUtilization) {
        this.cloudletCount = cloudletCount;
        this.cloudletLength = cloudletLength;
        this.cpuUtilization = cpuUtilization;
    }

    public int getCloudletCount() {
        return cloudletCount;
    }

    public long getCloudletLength() {
        return cloudletLength;
    }

    public double getCpuUtilization() {
        return cpuUtilization;
    }
}

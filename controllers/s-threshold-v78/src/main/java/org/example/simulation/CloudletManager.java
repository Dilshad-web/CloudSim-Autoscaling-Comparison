package org.example.simulation;

import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelDynamic;

import java.util.ArrayList;
import java.util.List;

public class CloudletManager {

    public List<Cloudlet> createCloudlets(int numberOfCloudlets) {
        return createCloudlets(numberOfCloudlets, 1000, 0.5);
    }

    /** Creates cloudlets for a particular workload phase. */
    public List<Cloudlet> createCloudlets(
            int numberOfCloudlets,
            long length,
            double cpuUtilization) {

        List<Cloudlet> cloudletList = new ArrayList<>();
        double boundedUtilization = Math.max(0.0, Math.min(1.0, cpuUtilization));

        for (int cloudletId = 0; cloudletId < numberOfCloudlets; cloudletId++) {
            Cloudlet cloudlet = new CloudletSimple(
                    length,
                    1,
                    new UtilizationModelDynamic(boundedUtilization)
            );

            cloudlet.setSizes(300);
            // Keep CPU as the workload driver. RAM/BW demand is intentionally
            // lower so multiple cloudlets can share a VM without exhausting
            // its auxiliary resources and triggering pathological scheduler delays.
            cloudlet.setUtilizationModelRam(new UtilizationModelDynamic(0.10));
            cloudlet.setUtilizationModelBw(new UtilizationModelDynamic(0.10));
            // Use an explicit zero submission delay so response/waiting metrics
            // are measured from the controlled workload arrival time.
            cloudlet.setSubmissionDelay(0.0);
            cloudletList.add(cloudlet);
        }

        return cloudletList;
    }

    /**
     * Creates cloudlets that arrive progressively over simulated time rather
     * than all at once. All-at-once submission is why scale-out couldn't
     * help: CloudSim Plus's broker had already assigned every cloudlet to a
     * VM from the initial set before any new VM existed, so newly added VMs
     * had nothing left to pick up. Staggering arrivals means cloudlets
     * submitted AFTER a scale-out event are still unassigned and can go to
     * the new VM through the broker's normal (unmodified, safe) assignment
     * logic -- no need to override VM selection ourselves.
     */
    public List<Cloudlet> createStaggeredCloudlets(
            int numberOfCloudlets,
            long length,
            double cpuUtilization,
            double gapSeconds) {

        List<Cloudlet> cloudletList = createCloudlets(numberOfCloudlets, length, cpuUtilization);
        for (int i = 0; i < cloudletList.size(); i++) {
            cloudletList.get(i).setSubmissionDelay(i * gapSeconds);
        }
        return cloudletList;
    }

    /**
     * Creates a trace-driven workload (used with the Bitbrains trace).
     *
     * Each trace step becomes a window of {@code stepSeconds} simulated seconds.
     * {@code offeredLoad[i]} is the load of that step as a fraction of the capacity
     * of {@code baselineVms} VMs (1.0 = they would be fully busy). The number of
     * cloudlets arriving in the step is
     *
     *     offeredLoad[i] * baselineVms * stepSeconds * mips / length
     *
     * Fractions are carried over to the next step so the total is preserved.
     * Arrivals are spread evenly inside the step. Each cloudlet asks for 100% of
     * one PE (like the paper benchmark), so VM utilization measured by CloudSim
     * is produced by the arrival pattern of the trace, not by a fixed constant.
     */
    public List<Cloudlet> createTraceCloudlets(
            double[] offeredLoad,
            int baselineVms,
            long mips,
            long length,
            double stepSeconds) {

        List<Cloudlet> cloudletList = new ArrayList<>();
        double capacityPerStep = baselineVms * stepSeconds * mips / (double) length;
        double carry = 0.0;

        for (int step = 0; step < offeredLoad.length; step++) {
            double expected = Math.max(0.0, offeredLoad[step]) * capacityPerStep + carry;
            int count = (int) Math.floor(expected);
            carry = expected - count;

            for (int j = 0; j < count; j++) {
                Cloudlet cloudlet = createCloudlets(1, length, 1.0).get(0);
                cloudlet.setSubmissionDelay(step * stepSeconds + j * (stepSeconds / count));
                cloudletList.add(cloudlet);
            }
        }
        return cloudletList;
    }

    public List<Cloudlet> createCloudlets(WorkloadProfile profile) {
        return createCloudlets(
                profile.getCloudletCount(),
                profile.getCloudletLength(),
                profile.getCpuUtilization()
        );
    }
}

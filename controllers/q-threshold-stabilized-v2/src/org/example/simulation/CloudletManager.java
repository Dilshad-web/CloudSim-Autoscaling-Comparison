package org.example.simulation;

import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelDynamic;

import java.util.ArrayList;
import java.util.List;

public class CloudletManager {

    /**
     * Creates cloudlets with the specified parameters.
     */
    public List<Cloudlet> createCloudlets(
            int numberOfCloudlets,
            long length,
            double cpuUtilization) {

        List<Cloudlet> cloudletList = new ArrayList<>();

        double boundedUtilization =
                Math.max(
                        0.0,
                        Math.min(
                                1.0,
                                cpuUtilization
                        )
                );

        for (int i = 0;
             i < numberOfCloudlets;
             i++) {

            Cloudlet cloudlet =
                    new CloudletSimple(
                            length,
                            1
                    );

            cloudlet.setSizes(300);
            cloudlet.setUtilizationModelRam(new UtilizationModelDynamic(0.10));
            cloudlet.setUtilizationModelBw(new UtilizationModelDynamic(0.10));
            cloudlet.setUtilizationModelCpu(new UtilizationModelDynamic(boundedUtilization));
            cloudlet.setSubmissionDelay(0.0);
            cloudletList.add(cloudlet);
        }

        return cloudletList;
    }
    /**
     * Creates a trace-driven workload using the same arrival-generation formula as the
     * S-Threshold experiment. Each trace step becomes a stepSeconds-wide arrival window.
     * offeredLoad is normalized to the capacity of baselineVms. Fractional expected
     * arrivals are carried forward so the total offered workload is preserved.
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

}
package org.example.simulation;

import org.cloudbus.cloudsim.vms.Vm;

import java.util.List;

public class WorkloadMonitor {

    private double totalCpuUtilization = 0.0;
    private int samples = 0;

    public void recordCpuUtilization(List<Vm> vmList) {

        if (vmList == null || vmList.isEmpty()) {
            return;
        }

        double totalUtilization = 0.0;

        for (Vm vm : vmList) {
            totalUtilization +=
                    vm.getCpuPercentUtilization();
        }

        double averageUtilization =
                totalUtilization / vmList.size();

        totalCpuUtilization +=
                averageUtilization;

        samples++;
    }

    public double getAverageCpuUtilization() {

        if (samples == 0) {
            return 0.0;
        }

        return totalCpuUtilization / samples;
    }

    public WorkloadState getWorkloadState() {

        double cpuUtilization =
                getAverageCpuUtilization() * 100;

        if (cpuUtilization < 30.0) {

            return WorkloadState.LOW;

        } else if (cpuUtilization <= 70.0) {

            return WorkloadState.MEDIUM;

        } else {

            return WorkloadState.HIGH;
        }
    }
}
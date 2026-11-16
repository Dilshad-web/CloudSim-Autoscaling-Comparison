package org.example.simulation;

import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;
import org.cloudbus.cloudsim.schedulers.cloudlet.CloudletSchedulerSpaceShared;

import java.util.ArrayList;
import java.util.List;

public class VmManager {

    public static final int MIN_VMS = 2;
    public static final int MAX_VMS = 40;

    public List<Vm> createVms(int numberOfVms) {
        List<Vm> vmList = new ArrayList<>();
        int boundedCount = Math.max(0, Math.min(numberOfVms, MAX_VMS));

        for (int vmId = 0; vmId < boundedCount; vmId++) {
            Vm vm = new VmSimple(750, 1);
            vm.setRam(512);
            vm.setBw(1000);
            vm.setSize(1000);
            // Space-shared execution keeps the benchmark timing stable and
            // prevents hundreds of simultaneous Cloudlets from creating
            // pathological time-shared scheduling delays.
            vm.setCloudletScheduler(new CloudletSchedulerSpaceShared());
            vmList.add(vm);
        }

        return vmList;
    }

    /** Creates VMs using the requested MIPS value for paper experiments. */
    public List<Vm> createVms(int numberOfVms, long mips) {
        List<Vm> vmList = new ArrayList<>();
        int boundedCount = Math.max(0, Math.min(numberOfVms, MAX_VMS));
        long boundedMips = Math.max(1, mips);

        for (int vmId = 0; vmId < boundedCount; vmId++) {
            Vm vm = new VmSimple(boundedMips, 1);
            vm.setRam(512);
            vm.setBw(1000);
            vm.setSize(1000);
            // Space-shared execution keeps the benchmark timing stable and
            // prevents hundreds of simultaneous Cloudlets from creating
            // pathological time-shared scheduling delays.
            vm.setCloudletScheduler(new CloudletSchedulerSpaceShared());
            vmList.add(vm);
        }
        return vmList;
    }

    public int applyScalingAction(int currentVmCount, ScalingAction action) {
        return switch (action) {
            case SCALE_OUT -> Math.min(MAX_VMS, currentVmCount + 1);
            case SCALE_IN -> Math.max(MIN_VMS, currentVmCount - 1);
            case NO_ACTION -> currentVmCount;
        };
    }
}

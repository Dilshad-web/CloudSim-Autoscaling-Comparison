
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
        return createVms(numberOfVms, 750);
    }

    public List<Vm> createVms(int numberOfVms, long mips) {
        if (numberOfVms < 0) {
            throw new IllegalArgumentException(
                    "Number of VMs cannot be negative."
            );
        }

        if (numberOfVms > MAX_VMS) {
            throw new IllegalArgumentException(
                    "Requested " + numberOfVms
                            + " VMs; maximum allowed per call is "
                            + MAX_VMS
            );
        }

        long boundedMips = Math.max(1, mips);
        List<Vm> vmList = new ArrayList<>();

        for (int i = 0; i < numberOfVms; i++) {
            Vm vm = new VmSimple(boundedMips, 1);

            vm.setRam(512);
            vm.setBw(1000);
            vm.setSize(1000);
            vm.setCloudletScheduler(
                    new CloudletSchedulerSpaceShared()
            );

            vmList.add(vm);
        }

        return vmList;
    }

    public int applyScalingAction(
            int currentVmCount,
            ScalingAction action
    ) {
        int boundedCurrent = Math.max(
                MIN_VMS,
                Math.min(currentVmCount, MAX_VMS)
        );

        return switch (action) {
            case SCALE_OUT ->
                    Math.min(MAX_VMS, boundedCurrent + 1);

            case SCALE_IN ->
                    Math.max(MIN_VMS, boundedCurrent - 1);

            case NO_ACTION -> boundedCurrent;
        };
    }
}

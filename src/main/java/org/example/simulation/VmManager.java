package org.example.simulation;

import org.cloudbus.cloudsim.resources.Pe;
import org.cloudbus.cloudsim.resources.PeSimple;
import org.cloudbus.cloudsim.vms.Vm;
import org.cloudbus.cloudsim.vms.VmSimple;

import java.util.ArrayList;
import java.util.List;

public class VmManager {

    public List<Vm> createVms(int numberOfVms) {

        List<Vm> vmList = new ArrayList<>();

        for (int vmId = 0; vmId < numberOfVms; vmId++) {

            Vm vm = new VmSimple(750, 1);

            vm.setRam(512);
            vm.setBw(1000);
            vm.setSize(1000);

            vmList.add(vm);
        }

        return vmList;
    }
}
package org.example.simulation;

import org.cloudbus.cloudsim.hosts.Host;
import org.cloudbus.cloudsim.hosts.HostSimple;
import org.cloudbus.cloudsim.resources.Pe;
import org.cloudbus.cloudsim.resources.PeSimple;
import org.cloudbus.cloudsim.schedulers.vm.VmSchedulerTimeShared;

import java.util.ArrayList;
import java.util.List;

public class HostManager {

    public List<Host> createHosts() {

        List<Host> hostList = new ArrayList<>();

        // Create 6 Physical Machines
        for (int hostId = 0; hostId < 6; hostId++) {

            List<Pe> peList = new ArrayList<>();

            // 4 CPU cores for each Physical Machine
            for (int coreId = 0; coreId < 4; coreId++) {
                peList.add(new PeSimple(100000));
            }

            Host host = new HostSimple(
                    11048,
                    150000,
                    1000000,
                    peList
            );

            // Allow all benchmark VMs to be placed across the hosts.
            // The paper experiment includes up to 40 VMs.
            host.setVmScheduler(new VmSchedulerTimeShared());

            hostList.add(host);
        }

        return hostList;
    }
}
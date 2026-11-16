package org.example.simulation;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;

public class CloudEnvironment {

    private final CloudSim simulation;
    private final Datacenter datacenter;

    public CloudEnvironment() {

        simulation = new CloudSim();

        HostManager hostManager = new HostManager();

        datacenter = new DatacenterSimple(
                simulation,
                hostManager.createHosts()
        );
    }

    public CloudSim getSimulation() {
        return simulation;
    }

    public Datacenter getDatacenter() {
        return datacenter;
    }
}

package org.example.simulation;

import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.datacenters.Datacenter;
import org.cloudbus.cloudsim.datacenters.DatacenterSimple;

public class CloudEnvironment {

    // CloudSim Plus only fires periodic "clock tick" events (used by the
    // S-Threshold+SARSA monitoring loop) at the Datacenter's scheduling
    // interval. The default is 0, which means the datacenter only updates
    // VM utilization on discrete cloudlet events -- ticks arrive too rarely
    // (sometimes almost never) for a live monitoring loop to see meaningful
    // utilization samples. Setting this makes CPU% get refreshed and clock
    // ticks fire every SCHEDULING_INTERVAL seconds of simulated time, which
    // is required for ResearchBenchmarkRunner's adaptive scaling loop to
    // actually observe HIGH/LOW utilization and act on it.
    private static final double SCHEDULING_INTERVAL = 1.0;

    private final CloudSim simulation;
    private final Datacenter datacenter;

    public CloudEnvironment() {

        simulation = new CloudSim();

        HostManager hostManager = new HostManager();

        datacenter = new DatacenterSimple(
                simulation,
                hostManager.createHosts()
        );

        datacenter.setSchedulingInterval(SCHEDULING_INTERVAL);
    }

    public CloudSim getSimulation() {
        return simulation;
    }

    public Datacenter getDatacenter() {
        return datacenter;
    }
}
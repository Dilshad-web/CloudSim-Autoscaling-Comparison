package org.example;

import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.vms.Vm;
import org.example.simulation.CloudEnvironment;
import org.example.simulation.CloudletManager;
import org.example.simulation.VmManager;

import java.util.List;

public class Main {

    public static void main(String[] args) {

        // Create cloud environment
        CloudEnvironment environment = new CloudEnvironment();

        // Create VMs
        VmManager vmManager = new VmManager();
        List<Vm> vmList = vmManager.createVms(2);

        // Create cloudlets
        CloudletManager cloudletManager = new CloudletManager();
        List<Cloudlet> cloudletList =
                cloudletManager.createCloudlets(4);

        // Create broker
        DatacenterBrokerSimple broker =
                new DatacenterBrokerSimple(
                        environment.getSimulation()
                );

        // Submit VMs and cloudlets
        broker.submitVmList(vmList);
        broker.submitCloudletList(cloudletList);

        // Start simulation
        environment.getSimulation().start();

        // Print results
        System.out.println("Simulation finished.");
        System.out.println("VMs created: "
                + broker.getVmCreatedList().size());
        System.out.println("Cloudlets finished: "
                + broker.getCloudletFinishedList().size());
    }
}
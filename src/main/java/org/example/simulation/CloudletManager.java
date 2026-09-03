package org.example.simulation;

import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.cloudlets.CloudletSimple;
import org.cloudbus.cloudsim.utilizationmodels.UtilizationModelDynamic;

import java.util.ArrayList;
import java.util.List;

public class CloudletManager {

    public List<Cloudlet> createCloudlets(int numberOfCloudlets) {

        List<Cloudlet> cloudletList = new ArrayList<>();

        for (int cloudletId = 0; cloudletId < numberOfCloudlets; cloudletId++) {

            Cloudlet cloudlet = new CloudletSimple(
                    1000,
                    1,
                    new UtilizationModelDynamic(0.5)
            );

            cloudlet.setSizes(300);

            cloudletList.add(cloudlet);
        }

        return cloudletList;
    }
}
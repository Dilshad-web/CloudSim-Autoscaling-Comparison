package org.example.simulation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/** Writes one CSV row for every workload-control cycle. */
public class MetricsCollector {
    private final Path outputFile;

    public MetricsCollector(String fileName) {
        this.outputFile = Path.of(fileName);
        try {
            Path parent = outputFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    outputFile,
                    "episode,phase,workload,cloudlets,initial_vms,final_vms,cpu_percent,state,threshold_action,sarsa_action,final_action,makespan,response_time,waiting_time,turnaround_time,throughput,reward,next_state,next_action,q_value\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            throw new RuntimeException("Unable to initialize metrics CSV", e);
        }
    }

    public void record(
            int episode,
            int phase,
            String workloadLabel,
            int cloudlets,
            int initialVms,
            int finalVms,
            double cpuPercent,
            WorkloadState state,
            ScalingAction thresholdAction,
            ScalingAction sarsaAction,
            ScalingAction finalAction,
            SimulationMetrics.Metrics metrics,
            double reward,
            WorkloadState nextState,
            ScalingAction nextAction,
            double qValue) {

        String row = String.format(Locale.US,
                "%d,%d,%s,%d,%d,%d,%.4f,%s,%s,%s,%s,%.6f,%.6f,%.6f,%.6f,%.6f,%.8f,%s,%s,%.8f%n",
                episode,
                phase,
                workloadLabel,
                cloudlets,
                initialVms,
                finalVms,
                cpuPercent,
                state,
                thresholdAction,
                sarsaAction,
                finalAction,
                metrics.makespan(),
                metrics.averageResponseTime(),
                metrics.averageWaitingTime(),
                metrics.averageTurnaroundTime(),
                metrics.throughput(),
                reward,
                nextState,
                nextAction,
                qValue);

        try {
            Files.writeString(outputFile, row, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException("Unable to write metrics CSV", e);
        }
    }

    public Path getOutputFile() {
        return outputFile;
    }
}

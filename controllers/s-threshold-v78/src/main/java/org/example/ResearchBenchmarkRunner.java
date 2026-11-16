package org.example;

import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.vms.Vm;
import org.example.simulation.CloudEnvironment;
import org.example.simulation.CloudletManager;
import org.example.simulation.MetricsCollector;
import org.example.simulation.RewardManager;
import org.example.simulation.SThresholdManager;
import org.example.simulation.SarsaAgent;
import org.example.simulation.ScalingAction;
import org.example.simulation.SimulationMetrics;
import org.example.simulation.VmManager;
import org.example.simulation.WorkloadState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Paper-aligned performance benchmark.
 *
 * The paper evaluates five metrics while varying cloudlets, MIPS and VMs.
 * This runner uses the same independent-variable ranges reported in the paper
 * and writes both raw observations and publication-style summary tables.
 *
 * Cloudlet and MIPS experiments run the S-Threshold + SARSA controller live
 * during the simulation: VM count starts at DEFAULT_VMS (matching the paper's
 * stated "VMs = 10" starting configuration) and is then scaled up/down by the
 * controller in response to measured CPU utilization, exactly as described in
 * the paper's Section 4 methodology and Pseudocode 1. The VM experiment keeps
 * VM count genuinely fixed at each value (10/20/30/40), because there the VM
 * count IS the independent variable being studied, not a starting point for
 * scaling — this matches how the paper's own Section 5.2.3 setup reads.
 */
public final class ResearchBenchmarkRunner {
    private static final int REPETITIONS = 3;
    private static final long CLOUDLET_LENGTH = 1000;
    private static final double CPU_UTILIZATION = 1.0;
    private static final int DEFAULT_MIPS = 750;
    private static final int DEFAULT_VMS = 10;
    private static final int DEFAULT_CLOUDLETS = 500;

    // How often (in simulated seconds) the controller polls utilization and
    // makes a scaling decision. Smaller = more responsive, more log rows.
    private static final double DECISION_INTERVAL = 2.0;

    // SARSA hyperparameters. Shared/persisted across the WHOLE benchmark run
    // (all cloudlet + MIPS data points) so the Q-table genuinely keeps
    // learning across episodes, matching the paper's "learns from experience
    // over time" framing rather than resetting on every data point.
    private static final double ALPHA = 0.1;
    private static final double GAMMA = 0.9;
    private static final double EPSILON = 0.1;

    private final CloudletManager cloudletManager = new CloudletManager();
    private final VmManager vmManager = new VmManager();
    private final SarsaAgent sarsaAgent = new SarsaAgent(ALPHA, GAMMA, EPSILON);
    private final SThresholdManager sThresholdManager = new SThresholdManager(sarsaAgent);
    private final RewardManager rewardManager = new RewardManager();
    private final AtomicInteger episodeCounter = new AtomicInteger(0);
    private MetricsCollector adaptiveLog;

    public void run() {
        Path resultsDir = Path.of("results");
        try {
            Files.createDirectories(resultsDir);
            Files.createDirectories(resultsDir.resolve("plots"));
        } catch (IOException e) {
            throw new RuntimeException("Unable to create results directory", e);
        }

        adaptiveLog = new MetricsCollector(resultsDir.resolve("adaptive_scaling_log.csv").toString());

        List<Result> results = new ArrayList<>();
        runCloudletExperiment(results);
        runMipsExperiment(results);
        runVmExperiment(results);

        writeRawCsv(results, resultsDir.resolve("research_raw_results.csv"));
        writeSummaryCsv(results, resultsDir.resolve("research_summary.csv"));
        writeMarkdownReport(results, resultsDir.resolve("research_results.md"));
        writePaperReference(resultsDir.resolve("paper_reference_comparison.md"));
        writeQTable(resultsDir.resolve("sarsa_q_table.md"));

        System.out.println("\n============================================================");
        System.out.println(" PAPER-ALIGNED PERFORMANCE BENCHMARK COMPLETE");
        System.out.println(" Repetitions per data point: " + REPETITIONS);
        System.out.println(" Results: results/research_summary.csv");
        System.out.println(" Tables : results/research_results.md");
        System.out.println(" Paper  : results/paper_reference_comparison.md");
        System.out.println(" Scaling log: results/adaptive_scaling_log.csv");
        System.out.println(" Q-table: results/sarsa_q_table.md");
        System.out.println(" Plots  : run python3 scripts/plot_results.py");
        System.out.println("============================================================");

        printConsoleTables(results);
        sarsaAgent.printQTable();
    }

    private void runCloudletExperiment(List<Result> results) {
        int[] cloudlets = {100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};
        for (int n : cloudlets) {
            results.add(runPoint("Cloudlets", n, n, DEFAULT_MIPS, DEFAULT_VMS, true));
        }
    }

    private void runMipsExperiment(List<Result> results) {
        int[] mipsValues = {600, 700, 800, 900, 1000};
        for (int mips : mipsValues) {
            results.add(runPoint("MIPS", mips, DEFAULT_CLOUDLETS, mips, DEFAULT_VMS, true));
        }
    }

    private void runVmExperiment(List<Result> results) {
        // VM count is the independent variable here, so it's held fixed per
        // point instead of being left to the controller to scale.
        int[] vmValues = {10, 20, 30, 40};
        for (int vms : vmValues) {
            results.add(runPoint("VMs", vms, DEFAULT_CLOUDLETS, 1000, vms, false));
        }
    }

    private Result runPoint(String experiment, int x, int cloudletCount, int mips, int vmCount, boolean adaptive) {
        double makespan = 0, response = 0, waiting = 0, turnaround = 0, throughput = 0;
        double finalVmsSum = 0;

        for (int repetition = 0; repetition < REPETITIONS; repetition++) {
            SimRunResult run = adaptive
                    ? runAdaptiveSimulation(experiment, x, cloudletCount, mips, vmCount)
                    : runStaticSimulation(cloudletCount, mips, vmCount);
            SimulationMetrics.Metrics metrics = run.metrics();

            makespan += metrics.makespan();
            response += metrics.averageResponseTime();
            waiting += metrics.averageWaitingTime();
            turnaround += metrics.averageTurnaroundTime();
            throughput += metrics.throughput();
            finalVmsSum += run.finalVmCount();
        }

        makespan /= REPETITIONS;
        response /= REPETITIONS;
        waiting /= REPETITIONS;
        turnaround /= REPETITIONS;
        throughput /= REPETITIONS;
        // Report the VM count the simulation actually ended up running with,
        // not the starting/input count. For the fixed-VM experiment these are
        // the same; for the adaptive cloudlet/MIPS experiments they usually
        // differ because the controller scales out during the run.
        int finalVms = (int) Math.round(finalVmsSum / REPETITIONS);

        return new Result(experiment, x, cloudletCount, mips, finalVms,
                makespan, response, waiting, turnaround, throughput);
    }

    /** Fixed-capacity run: no scaling. Used only for the VM-count experiment. */
    private SimRunResult runStaticSimulation(int cloudletCount, int mips, int vmCount) {
        CloudEnvironment environment = new CloudEnvironment();
        List<Vm> vmList = vmManager.createVms(vmCount, mips);
        List<Cloudlet> cloudlets = cloudletManager.createCloudlets(
                cloudletCount, CLOUDLET_LENGTH, CPU_UTILIZATION);

        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(environment.getSimulation());
        broker.submitVmList(vmList);
        broker.submitCloudletList(cloudlets);
        environment.getSimulation().start();

        return new SimRunResult(SimulationMetrics.calculate(broker.getCloudletFinishedList()), vmCount);
    }

    /** Pairs a simulation's measured metrics with the VM count it actually ran with. */
    private record SimRunResult(SimulationMetrics.Metrics metrics, int finalVmCount) {}

    /**
     * Runs the simulation with the S-Threshold + SARSA controller live: every
     * DECISION_INTERVAL of simulated time it samples average VM CPU
     * utilization, derives the workload state, asks SARSA + the threshold
     * rules for a final action, applies it (adds/removes a VM), computes a
     * reward, and performs the SARSA Q-update one tick later once the actual
     * next state/action are known — matching Pseudocode 1 in the paper.
     */
    private SimRunResult runAdaptiveSimulation(
            String experiment, int x, int cloudletCount, int mips, int initialVmCount) {

        CloudEnvironment environment = new CloudEnvironment();
        List<Vm> vmList = vmManager.createVms(initialVmCount, mips);

        List<Cloudlet> cloudlets = cloudletManager.createStaggeredCloudlets(
                cloudletCount, CLOUDLET_LENGTH, CPU_UTILIZATION, 0.1);


        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(environment.getSimulation());


// Keep the original arrival order so future cloudlets can be rebound
// when the VM set changes.
        final List<Cloudlet> ordered = new ArrayList<>(cloudlets);
        int[] roundRobin = {0};

        broker.submitVmList(vmList);
        broker.submitCloudletList(cloudlets);

        // Mutable holders so the clock-tick lambda can carry state across ticks.
        double[] lastDecisionTime = {0.0};
        WorkloadState[] pendingState = {null};
        ScalingAction[] pendingAction = {null};
        double[] pendingReward = {0.0};
        int[] lastVmCount = {initialVmCount};

        // Diagnostic counters, printed at the end of this run so we can see
        // directly whether decisions are firing and whether they're actually
        // being applied (vs. blocked by MAX_VMS / no idle VM to remove).
        int[] ticks = {0};
        int[] scaleOutApplied = {0};
        int[] scaleOutBlocked = {0};
        int[] scaleInApplied = {0};
        int[] scaleInBlocked = {0};
        int[] noAction = {0};

        environment.getSimulation().addOnClockTickListener(info -> {
            double now = info.getTime();
            if (now - lastDecisionTime[0] < DECISION_INTERVAL) {
                return;
            }
            lastDecisionTime[0] = now;
            ticks[0]++;

            List<Vm> execList = broker.getVmExecList();
            if (execList.isEmpty()) {
                return;
            }

            double avgCpu = execList.stream()
                    .mapToDouble(Vm::getCpuPercentUtilization)
                    .average()
                    .orElse(0.0) * 100.0;

            WorkloadState state = sThresholdManager.getWorkloadState(avgCpu);
            ScalingAction sarsaAction = sThresholdManager.getSarsaAction(state);
            ScalingAction finalAction = sThresholdManager.decideFinalAction(avgCpu, sarsaAction);

            int before = execList.size();
            int after = before;

            if (finalAction == ScalingAction.SCALE_OUT) {
                if (before < VmManager.MAX_VMS) {
                    Vm newVm = vmManager.createVms(1, mips).get(0);
                    broker.submitVm(newVm);
                    after = before + 1;
                    scaleOutApplied[0]++;

                    // Rebind future cloudlets across all currently active VMs,
                    // including the newly created VM.
                    // Rebind future cloudlets across the existing VMs
// plus the newly created VM.
                    List<Vm> targets = new ArrayList<>(execList);
                    targets.add(newVm);
                    for (Cloudlet cloudlet : ordered) {
                        if (!cloudlet.isFinished()
                                && cloudlet.getSubmissionDelay() > now
                                && !targets.isEmpty()) {
                            cloudlet.setVm(
                                    targets.get(roundRobin[0]++ % targets.size())
                            );
                        }
                    }
                } else {
                    scaleOutBlocked[0]++;
                }

            } else if (finalAction == ScalingAction.SCALE_IN) {

                if (before > VmManager.MIN_VMS) {
                    Vm idleVm = execList.stream()
                            .filter(v -> v.getCloudletScheduler().getCloudletExecList().isEmpty())
                            .filter(v -> v.getCloudletScheduler().getCloudletWaitingList().isEmpty())
                            .findFirst()
                            .orElse(null);

                    if (idleVm != null) {

                        // Before destroying the VM, move future cloudlets away from it.
                        List<Vm> targets = new ArrayList<>(execList);
                        targets.remove(idleVm);

                        for (Cloudlet cloudlet : ordered) {
                            if (!cloudlet.isFinished()
                                    && cloudlet.getSubmissionDelay() > now
                                    && !targets.isEmpty()) {
                                cloudlet.setVm(
                                        targets.get(roundRobin[0]++ % targets.size())
                                );
                            }
                        }

                        broker.destroyVm(idleVm);
                        after = before - 1;
                        scaleInApplied[0]++;

                    } else {
                        scaleInBlocked[0]++;
                    }

                } else {
                    scaleInBlocked[0]++;
                }

            } else {
                noAction[0]++;
            }

            // Response-time proxy: cloudlets still waiting for a VM. More
            // backlog -> worse reward, mirroring "minimize response time".
            double backlogProxy = broker.getCloudletWaitingList().size() + 1.0;
            double reward = rewardManager.calculateReward(backlogProxy);

            // Complete the SARSA update from the PREVIOUS tick now that we
            // actually know the next state/action (proper on-policy update).
            if (pendingState[0] != null) {
                sarsaAgent.updateQValue(pendingState[0], pendingAction[0], pendingReward[0], state, sarsaAction);
            }

            int episode = episodeCounter.incrementAndGet();
            SimulationMetrics.Metrics placeholder = new SimulationMetrics.Metrics(0, 0, 0, 0, 0, 0);
            adaptiveLog.record(episode, episode, experiment + "=" + x, cloudletCount, lastVmCount[0], after,
                    avgCpu, state, sThresholdManager.getThresholdAction(avgCpu), sarsaAction, finalAction,
                    placeholder, reward, state, sarsaAction, sarsaAgent.getQValue(state, sarsaAction));

            pendingState[0] = state;
            pendingAction[0] = sarsaAction;
            pendingReward[0] = reward;
            lastVmCount[0] = after;
        });

        environment.getSimulation().start();
        int finishedCloudlets = broker.getCloudletFinishedList().size();

        if (finishedCloudlets != cloudlets.size()) {
            throw new IllegalStateException(
                    "INCOMPLETE RUN: " +
                            experiment + "=" + x +
                            ", finished=" + finishedCloudlets +
                            ", expected=" + cloudlets.size()
            );
        }


        // Terminal SARSA update for the last pending decision of this run.
        if (pendingState[0] != null) {
            sarsaAgent.updateTerminalQValue(pendingState[0], pendingAction[0], pendingReward[0]);
        }

        System.out.printf(Locale.US,
                "[adaptive] %s=%d ticks=%d scaleOutApplied=%d scaleOutBlocked(maxVms)=%d "
                        + "scaleInApplied=%d scaleInBlocked(noIdleVm/minVms)=%d noAction=%d finalVMs=%d%n",
                experiment, x, ticks[0], scaleOutApplied[0], scaleOutBlocked[0],
                scaleInApplied[0], scaleInBlocked[0], noAction[0], lastVmCount[0]);

        return new SimRunResult(
                SimulationMetrics.calculate(broker.getCloudletFinishedList()),
                lastVmCount[0]);
    }

    private void writeQTable(Path file) {
        StringBuilder sb = new StringBuilder();
        sb.append("# SARSA Q-Table (learned across the full benchmark run)\n\n");
        sb.append(String.format(Locale.US, "Parameters: alpha = %.2f, gamma = %.2f, epsilon = %.2f\n\n", ALPHA, GAMMA, EPSILON));
        sb.append("| State | SCALE_OUT | SCALE_IN | NO_ACTION |\n|---|---:|---:|---:|\n");
        for (WorkloadState state : WorkloadState.values()) {
            sb.append(String.format(Locale.US, "| %s | %.6f | %.6f | %.6f |%n",
                    state,
                    sarsaAgent.getQValue(state, ScalingAction.SCALE_OUT),
                    sarsaAgent.getQValue(state, ScalingAction.SCALE_IN),
                    sarsaAgent.getQValue(state, ScalingAction.NO_ACTION)));
        }
        write(file, sb.toString());
    }

    private void writeRawCsv(List<Result> results, Path file) {
        StringBuilder sb = new StringBuilder();
        sb.append("experiment,x,cloudlets,mips,vms,makespan,response_time,waiting_time,turnaround_time,throughput\n");
        for (Result r : results) {
            sb.append(String.format(Locale.US,
                    "%s,%d,%d,%d,%d,%.8f,%.8f,%.8f,%.8f,%.8f%n",
                    r.experiment(), r.x(), r.cloudlets(), r.mips(), r.vms(),
                    r.makespan(), r.response(), r.waiting(), r.turnaround(), r.throughput()));
        }
        write(file, sb.toString());
    }

    private void writeSummaryCsv(List<Result> results, Path file) {
        StringBuilder sb = new StringBuilder();
        sb.append("experiment,independent_variable,value,cloudlets,mips,vms,makespan,response_time,waiting_time,turnaround_time,throughput\n");
        for (Result r : results) {
            sb.append(String.format(Locale.US,
                    "%s,%s,%d,%d,%d,%d,%.8f,%.8f,%.8f,%.8f,%.8f%n",
                    r.experiment(), r.experiment(), r.x(), r.cloudlets(), r.mips(), r.vms(),
                    r.makespan(), r.response(), r.waiting(), r.turnaround(), r.throughput()));
        }
        write(file, sb.toString());
    }

    private void writeMarkdownReport(List<Result> results, Path file) {
        StringBuilder sb = new StringBuilder();
        sb.append("# S-Threshold CloudSim Plus — Research Results\n\n");
        sb.append("These tables contain values measured by our CloudSim Plus implementation. "
                + "They use the five metrics evaluated in the paper. Time values are shown in "
                + "CloudSim's native simulation-clock units, which CloudSim Plus documents as seconds "
                + "(time = Cloudlet length in MI / VM MIPS). The paper's figures label their time axes "
                + "\"ms\", but do not show a conversion step; given the paper's own Table 3 parameters "
                + "(750 MIPS VMs, length-1000 cloudlets -> ~1.33s per cloudlet), the paper's plotted values "
                + "are consistent in *order of magnitude* with raw CloudSim seconds, not true milliseconds "
                + "(which would be ~1000x larger). Treat the paper's \"ms\" label as likely a mislabeling, "
                + "and compare these raw values directly against the paper's numbers before applying any "
                + "unit conversion.\n\n");
        sb.append("## 1. Different numbers of Cloudlets\n\n");
        appendTable(sb, results, "Cloudlets");
        sb.append("\n## 2. Different MIPS\n\n");
        appendTable(sb, results, "MIPS");
        sb.append("\n## 3. Different numbers of VMs\n\n");
        appendTable(sb, results, "VMs");
        sb.append("\n## Interpretation\n\n");
        sb.append("For makespan, response time, waiting time and turnaround time, lower is better. "
                + "For throughput, higher is better. The paper reports the same five metrics and "
                + "evaluates cloudlets 100–1000, MIPS 600–1000, and VMs 10/20/30/40.\n");
        write(file, sb.toString());
    }

    private void appendTable(StringBuilder sb, List<Result> results, String experiment) {
        sb.append("| ").append(experiment)
                .append(" | Cloudlets | MIPS | VMs | Makespan | Response | Waiting | Turnaround | Throughput |\n");
        sb.append("|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Result r : results) {
            if (!r.experiment().equals(experiment)) continue;
            sb.append(String.format(Locale.US,
                    "| %d | %d | %d | %d | %.4f | %.4f | %.4f | %.4f | %.6f |%n",
                    r.x(), r.cloudlets(), r.mips(), r.vms(), r.makespan(), r.response(),
                    r.waiting(), r.turnaround(), r.throughput()));
        }
    }

    private void writePaperReference(Path file) {
        String text = """
                # Research-paper reference comparison

                The supplied 2025 paper reports the following experimental design:

                | Experiment | Independent variable | Fixed values |
                |---|---|---|
                | Cloudlets | 100 to 1000 | 10 VMs |
                | MIPS | 600 to 1000 | 10 VMs, 500 Cloudlets |
                | VMs | 10, 20, 30, 40 | 1000 MIPS, 500 Cloudlets |

                The paper evaluates makespan, response time, waiting time, average turnaround time and throughput.

                ## What the paper explicitly reports

                For the increasing-cloudlet experiment, the paper reports overall improvements of **64%, 72%, 78%, 75%, and 60%** relative to queueing theory, Q-Threshold, threshold, Q-learning and ATTLB, respectively.

                For the increasing-MIPS experiment it reports **75%, 78%, 80%, 76%, and 82%** improvements against those same methods.

                For the increasing-VM experiment it reports **52%, 60%, 62%, 68%, and 50%** improvements against those methods.

                ## Important limitation

                The paper presents the detailed comparison as graphs (Figures 4–6), but does not provide the underlying numerical data points as a table. Therefore this project **does not invent exact paper datapoints**. The CSV/Markdown tables contain our measured CloudSim values, while this file records the paper's directly stated ranges, trends and aggregate improvement percentages.

                ## Expected trends from the paper

                - Increasing cloudlets: makespan and turnaround generally increase; the proposed method is reported as lower than the comparison methods, while throughput is higher.
                - Increasing MIPS: makespan, response, waiting and turnaround decrease; throughput increases.
                - Increasing VMs: makespan, response, waiting and turnaround decrease; throughput increases.
                """;
        write(file, text);
    }

    private void printConsoleTables(List<Result> results) {
        for (String experiment : List.of("Cloudlets", "MIPS", "VMs")) {
            System.out.println("\n--- " + experiment + " EXPERIMENT ---");
            System.out.printf("%-10s %-10s %-8s %-8s %-12s %-12s %-12s %-12s %-12s%n",
                    experiment, "Cloudlets", "MIPS", "VMs", "Makespan", "Response", "Waiting", "Turnaround", "Throughput");
            for (Result r : results) {
                if (!r.experiment().equals(experiment)) continue;
                System.out.printf(Locale.US,
                        "%-10d %-10d %-8d %-8d %-12.4f %-12.4f %-12.4f %-12.4f %-12.6f%n",
                        r.x(), r.cloudlets(), r.mips(), r.vms(), r.makespan(), r.response(),
                        r.waiting(), r.turnaround(), r.throughput());
            }
        }
    }

    private void write(Path file, String content) {
        try {
            Files.writeString(file, content,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new RuntimeException("Unable to write " + file, e);
        }
    }

    private record Result(
            String experiment,
            int x,
            int cloudlets,
            int mips,
            int vms,
            double makespan,
            double response,
            double waiting,
            double turnaround,
            double throughput) {}
}

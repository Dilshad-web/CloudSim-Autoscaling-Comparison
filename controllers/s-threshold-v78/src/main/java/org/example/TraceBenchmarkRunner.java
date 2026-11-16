package org.example;

import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.vms.Vm;
import org.example.simulation.BitbrainsTraceLoader;
import org.example.simulation.CloudEnvironment;
import org.example.simulation.CloudletManager;
import org.example.simulation.RunRecord;
import org.example.simulation.RewardManager;
import org.example.simulation.SThresholdManager;
import org.example.simulation.SarsaAgent;
import org.example.simulation.ScalingAction;
import org.example.simulation.VmManager;
import org.example.simulation.WorkloadState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Trace-driven experiment on the Bitbrains workload.
 *
 * The same trace window is replayed three times:
 *   STATIC            - fixed number of VMs, no scaling (reference)
 *   THRESHOLD_ONLY    - the 30% / 70% rule alone, no SARSA confirmation
 *   S_THRESHOLD_SARSA - the project's controller (threshold + SARSA)
 *
 * This is a behaviour study, separate from the paper-aligned benchmark. It does
 * not reproduce any figure of the paper.
 *
 * How the trace becomes load: the mean CPU% of the Bitbrains VMs in each step
 * (5 minutes in the real trace) is rescaled, then turned into cloudlet arrivals
 * (see CloudletManager.createTraceCloudlets). One trace step lasts STEP_SECONDS
 * of simulated time. The rescaling is needed because Bitbrains VMs are mostly
 * lightly loaded: the P95 of the aggregated series is mapped to TARGET_P95_LOAD
 * of the baseline capacity. Both raw and scaled values are saved.
 *
 * Settings can be changed with JVM options, for example
 *   -Dbitbrains.dir=data/bitbrains -Dbitbrains.start=0 -Dbitbrains.steps=288
 *   -Dbitbrains.vms=0 -Dbitbrains.stepSeconds=10 -Dbitbrains.p95Load=0.9
 */
public final class TraceBenchmarkRunner {

    public enum Mode { STATIC, THRESHOLD_ONLY, S_THRESHOLD_SARSA }

    private static final String DATA_DIR = System.getProperty("bitbrains.dir", "data/bitbrains");
    private static final int MAX_VM_FILES = Integer.getInteger("bitbrains.vms", 100);
    private static final int WINDOW_START = Integer.getInteger("bitbrains.start", 0);
    private static final int WINDOW_STEPS = Integer.getInteger("bitbrains.steps", 288);
    private static final double STEP_SECONDS =
            Double.parseDouble(System.getProperty("bitbrains.stepSeconds", "10"));
    private static final double TARGET_P95_LOAD =
            Double.parseDouble(System.getProperty("bitbrains.p95Load", "0.9"));

    private static final int BASELINE_VMS = 10;
    private static final long MIPS = 750;
    private static final long CLOUDLET_LENGTH = 1000;
    private static final double DECISION_INTERVAL = 2.0;
    private static final int SARSA_REPETITIONS = 3;

    private static final double ALPHA = 0.1;
    private static final double GAMMA = 0.9;
    private static final double EPSILON = 0.1;

    private final CloudletManager cloudletManager = new CloudletManager();
    private final VmManager vmManager = new VmManager();
    private final RewardManager rewardManager = new RewardManager();
    private final SarsaAgent sarsaAgent = new SarsaAgent(ALPHA, GAMMA, EPSILON);
    private final SThresholdManager sThresholdManager = new SThresholdManager(sarsaAgent);

    private final StringBuilder timeline = new StringBuilder();

    public static boolean isDatasetAvailable() {
        return BitbrainsTraceLoader.isAvailable(Path.of(DATA_DIR));
    }

    public static String datasetDirectory() {
        return DATA_DIR;
    }


    // ------------------------------------------------------------------------------------
    // Batch API, used by TraceBatchRunner (multi-day Bitbrains experiment).
    // The simulation logic itself (runMode) is unchanged.
    // ------------------------------------------------------------------------------------

    private static final String TIMELINE_HEADER =
            "mode,repetition,time,trace_step,trace_cpu_percent,offered_load_percent,sim_cpu_percent,"
                    + "state,threshold_action,sarsa_action,final_action,vms_before,vms_after,backlog\n";

    public static double targetP95Load() {
        return TARGET_P95_LOAD;
    }

    public static double stepSeconds() {
        return STEP_SECONDS;
    }

    public static int sarsaRepetitions() {
        return SARSA_REPETITIONS;
    }

    /** Number of runs per simulated day: STATIC + THRESHOLD_ONLY + the SARSA repetitions. */
    public static int runsPerDay() {
        return 2 + SARSA_REPETITIONS;
    }

    /** Cloudlets that a load of 1.0 (fully busy baseline pool) produces in one trace step. */
    public static double capacityPerStep() {
        return BASELINE_VMS * STEP_SECONDS * MIPS / (double) CLOUDLET_LENGTH;
    }

    /** All fixed experiment parameters as text; results made with different parameters must not be mixed. */
    public static String settingsSignature() {
        return "baselineVms=" + BASELINE_VMS + ";mips=" + MIPS + ";cloudletLength=" + CLOUDLET_LENGTH
                + ";decisionInterval=" + DECISION_INTERVAL + ";sarsaRepetitions=" + SARSA_REPETITIONS
                + ";alpha=" + ALPHA + ";gamma=" + GAMMA + ";epsilon=" + EPSILON
                + ";stepSeconds=" + STEP_SECONDS + ";targetP95Load=" + TARGET_P95_LOAD;
    }

    /** The SARSA agent shared by all days and repetitions (its Q-table is saved/loaded by the batch). */
    public SarsaAgent sarsaAgent() {
        return sarsaAgent;
    }

    /** Controller timeline (repetition 1 of each mode) of the last runDay call. */
    public String timelineCsv() {
        return timeline.toString();
    }

    /**
     * Runs one simulated day: STATIC, THRESHOLD_ONLY and the SARSA repetitions, all on exactly the same
     * offeredLoad sequence. Records are added to {@code out} as each run finishes, so if a run throws,
     * the earlier records are still available to the caller. The SARSA Q-table keeps learning across the
     * repetitions (and across days, if the same runner instance is used for the next day).
     *
     * @param dayTrace    the raw trace window of this day (for the timeline and the step count)
     * @param offeredLoad offered load per step, already normalised with the batch's global scale
     */
    public void runDay(BitbrainsTraceLoader.Trace dayTrace, double[] offeredLoad, List<RunRecord> out) {
        timeline.setLength(0);
        timeline.append(TIMELINE_HEADER);
        out.add(toRecord(runMode(Mode.STATIC, 1, dayTrace, offeredLoad), 1));
        out.add(toRecord(runMode(Mode.THRESHOLD_ONLY, 1, dayTrace, offeredLoad), 1));
        for (int rep = 1; rep <= SARSA_REPETITIONS; rep++) {
            out.add(toRecord(runMode(Mode.S_THRESHOLD_SARSA, rep, dayTrace, offeredLoad), rep));
        }
    }

    private static RunRecord toRecord(Summary s, int repetition) {
        return new RunRecord(s.mode(), repetition, s.cloudlets(), s.completed(), s.lost(),
                s.makespan(), s.avgResponse(), s.avgWaiting(), s.p95Response(), s.throughput(),
                s.avgVms(), s.vmSeconds(), s.scaleOut(), s.scaleIn(), s.flaps());
    }

    public void run() {
        Path resultsDir = Path.of("results");
        try {
            Files.createDirectories(resultsDir);
        } catch (IOException e) {
            throw new RuntimeException("Unable to create results directory", e);
        }

        BitbrainsTraceLoader.Trace full = BitbrainsTraceLoader.load(Path.of(DATA_DIR), MAX_VM_FILES);
        BitbrainsTraceLoader.Trace trace = full.window(WINDOW_START, WINDOW_STEPS);
        if (trace.steps() == 0) {
            throw new IllegalStateException("Trace window is empty (start=" + WINDOW_START
                    + ", trace has " + full.steps() + " steps)");
        }

        double p95 = trace.percentile(0.95);
        double scale = p95 > 0 ? (TARGET_P95_LOAD * 100.0) / p95 : 1.0;
        double[] offeredLoad = new double[trace.steps()];
        for (int i = 0; i < offeredLoad.length; i++) {
            offeredLoad[i] = trace.cpuPercent()[i] / 100.0 * scale;
        }

        System.out.printf(Locale.US,
                "%n[bitbrains] VM files=%d, trace steps=%d (using %d from step %d), mean CPU=%.2f%%, P95=%.2f%%, "
                        + "load scale x%.2f (P95 -> %.0f%% of baseline capacity)%n",
                full.vmFiles(), full.steps(), trace.steps(), WINDOW_START, trace.mean(), p95,
                scale, TARGET_P95_LOAD * 100.0);

        writeTraceUsed(resultsDir.resolve("bitbrains_trace_used.csv"), trace, offeredLoad);

        timeline.append("mode,repetition,time,trace_step,trace_cpu_percent,offered_load_percent,sim_cpu_percent,"
                + "state,threshold_action,sarsa_action,final_action,vms_before,vms_after,backlog\n");

        List<Summary> summaries = new ArrayList<>();
        summaries.add(runMode(Mode.STATIC, 1, trace, offeredLoad));
        summaries.add(runMode(Mode.THRESHOLD_ONLY, 1, trace, offeredLoad));

        List<Summary> sarsaRuns = new ArrayList<>();
        for (int rep = 1; rep <= SARSA_REPETITIONS; rep++) {
            sarsaRuns.add(runMode(Mode.S_THRESHOLD_SARSA, rep, trace, offeredLoad));
        }
        summaries.add(average(sarsaRuns));

        write(resultsDir.resolve("bitbrains_timeline.csv"), timeline.toString());
        writeSummary(resultsDir.resolve("bitbrains_summary.csv"), resultsDir.resolve("bitbrains_summary.md"),
                summaries, trace, p95, scale);

        System.out.println("\n============================================================");
        System.out.println(" BITBRAINS TRACE EXPERIMENT COMPLETE");
        System.out.println(" Summary : results/bitbrains_summary.md");
        System.out.println(" Timeline: results/bitbrains_timeline.csv");
        System.out.println(" Trace   : results/bitbrains_trace_used.csv");
        System.out.println("============================================================");
        printSummary(summaries);
    }

    private Summary runMode(Mode mode, int repetition, BitbrainsTraceLoader.Trace trace, double[] offeredLoad) {
        CloudEnvironment environment = new CloudEnvironment();
        List<Vm> vmList = vmManager.createVms(BASELINE_VMS, MIPS);
        List<Cloudlet> cloudlets = cloudletManager.createTraceCloudlets(
                offeredLoad, BASELINE_VMS, MIPS, CLOUDLET_LENGTH, STEP_SECONDS);
        final int totalCloudlets = cloudlets.size();
        // createTraceCloudlets() returns them in arrival order (arrival = submission delay).
        final List<Cloudlet> ordered = new ArrayList<>(cloudlets);

        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(environment.getSimulation());
        // Keep VMs alive between sparse arrivals: only the controller (or nobody,
        // in STATIC mode) decides when a VM goes away during the trace.
        broker.setVmDestructionDelay(trace.steps() * STEP_SECONDS + 600.0);
        broker.submitVmList(vmList);
        broker.submitCloudletList(cloudlets);

        double[] lastTick = {0.0};
        double[] lastDecision = {0.0};
        double[] vmSeconds = {0.0};
        double[] cpuSum = {0.0};
        int[] cpuSamples = {0};
        int[] highTicks = {0};
        int[] scaleOut = {0};
        int[] scaleIn = {0};
        int[] flaps = {0};
        ScalingAction[] lastApplied = {null};
        int[] vmCount = {BASELINE_VMS};
        WorkloadState[] pendingState = {null};
        ScalingAction[] pendingAction = {null};
        double[] pendingReward = {0.0};
        int[] firstPending = {0};     // first cloudlet (in arrival order) that has not arrived yet
        int[] firstUnfinished = {0};  // lets us skip the finished cloudlets quickly
        int[] roundRobin = {0};
        int[] lostOnDestroy = {0};
        StringBuilder localTimeline = new StringBuilder();

        environment.getSimulation().addOnClockTickListener(info -> {
            double now = info.getTime();
            if (broker.getCloudletFinishedList().size() >= totalCloudlets) {
                return; // workload is done: stop accounting and scaling
            }

            List<Vm> execList = broker.getVmExecList();
            if (execList.isEmpty()) {
                return;
            }
            double dt = Math.max(0.0, now - lastTick[0]);
            lastTick[0] = now;
            vmSeconds[0] += execList.size() * dt;
            vmCount[0] = execList.size();

            if (mode == Mode.STATIC || now - lastDecision[0] < DECISION_INTERVAL) {
                return;
            }
            lastDecision[0] = now;

            double avgCpu = execList.stream().mapToDouble(Vm::getCpuPercentUtilization).average().orElse(0.0) * 100.0;
            cpuSum[0] += avgCpu;
            cpuSamples[0]++;
            if (avgCpu > sThresholdManager.getHighThreshold()) {
                highTicks[0]++;
            }

            WorkloadState state = sThresholdManager.getWorkloadState(avgCpu);
            ScalingAction thresholdAction = sThresholdManager.getThresholdAction(avgCpu);
            ScalingAction sarsaAction = ScalingAction.NO_ACTION;
            ScalingAction finalAction = thresholdAction;
            if (mode == Mode.S_THRESHOLD_SARSA) {
                sarsaAction = sThresholdManager.getSarsaAction(state);
                finalAction = sThresholdManager.decideFinalAction(avgCpu, sarsaAction);
            }

            int before = execList.size();
            int after = before;
            ScalingAction applied = null;

            // CloudSim Plus binds every cloudlet to a VM when it is submitted (time 0),
            // not when it arrives. So VMs added later would never get work, and a VM
            // removed later would silently lose the cloudlets still bound to it.
            // Fix: until a cloudlet has really arrived, keep re-binding it to the VMs
            // that exist now (the datacenter reads cloudlet.getVm() when it arrives).
            while (firstPending[0] < ordered.size()
                    && ordered.get(firstPending[0]).getSubmissionDelay() <= now) {
                firstPending[0]++;
            }
            while (firstUnfinished[0] < firstPending[0] && ordered.get(firstUnfinished[0]).isFinished()) {
                firstUnfinished[0]++;
            }
            // VMs that hold cloudlets which arrived (or arrive right now) and are not finished
            Set<Vm> inUse = new HashSet<>();
            for (int i = firstUnfinished[0]; i < firstPending[0]; i++) {
                Cloudlet c = ordered.get(i);
                if (!c.isFinished()) {
                    inUse.add(c.getVm());
                }
            }

            // 1. Scale-in candidate (same rule as before): a VM with no arrived, unfinished cloudlets.
            Vm removeCandidate = null;
            if (finalAction == ScalingAction.SCALE_IN && before > VmManager.MIN_VMS) {
                removeCandidate = execList.stream().filter(v -> !inUse.contains(v)).findFirst().orElse(null);
            }

            // 2. Scale-out FIRST, so the new VM exists before future cloudlets are assigned.
            Vm newVm = null;
            if (finalAction == ScalingAction.SCALE_OUT && before < VmManager.MAX_VMS) {
                newVm = vmManager.createVms(1, MIPS).get(0);
                broker.submitVm(newVm);
                after = before + 1;
                scaleOut[0]++;
                applied = ScalingAction.SCALE_OUT;
            }

            // 3. Rebind target list, built AFTER the scale-out decision:
            //    current VMs, minus the VM about to be removed, plus the newly created VM.
            List<Vm> targets = new ArrayList<>(execList);
            if (removeCandidate != null) {
                targets.remove(removeCandidate);
            }
            if (newVm != null) {
                targets.add(newVm);
            }

            // 4. Rebind every cloudlet that has not arrived yet.
            for (int i = firstPending[0]; i < ordered.size(); i++) {
                ordered.get(i).setVm(targets.get(roundRobin[0]++ % targets.size()));
            }

            // 5. Only now remove the scale-in VM: nothing is bound to it any more.
            if (removeCandidate != null) {
                lostOnDestroy[0] += broker.destroyVm(removeCandidate).size();
                after = before - 1;
                scaleIn[0]++;
                applied = ScalingAction.SCALE_IN;
            }
            if (applied != null) {
                if (lastApplied[0] != null && lastApplied[0] != applied) {
                    flaps[0]++; // direction reversal = flapping
                }
                lastApplied[0] = applied;
            }

            int backlog = broker.getCloudletWaitingList().size();
            if (mode == Mode.S_THRESHOLD_SARSA) {
                double reward = rewardManager.calculateReward(backlog + 1.0);
                if (pendingState[0] != null) {
                    sarsaAgent.updateQValue(pendingState[0], pendingAction[0], pendingReward[0], state, sarsaAction);
                }
                pendingState[0] = state;
                pendingAction[0] = sarsaAction;
                pendingReward[0] = reward;
            }

            int step = (int) Math.min(offeredLoad.length - 1, Math.floor(now / STEP_SECONDS));
            localTimeline.append(String.format(Locale.US,
                    "%s,%d,%.2f,%d,%.4f,%.4f,%.4f,%s,%s,%s,%s,%d,%d,%d%n",
                    mode, repetition, now, step,
                    trace.cpuPercent()[step], offeredLoad[step] * 100.0, avgCpu,
                    state, thresholdAction, sarsaAction, finalAction, before, after, backlog));
            vmCount[0] = after;
        });

        environment.getSimulation().start();

        if (mode == Mode.S_THRESHOLD_SARSA && pendingState[0] != null) {
            sarsaAgent.updateTerminalQValue(pendingState[0], pendingAction[0], pendingReward[0]);
        }
        if (repetition == 1) {
            timeline.append(localTimeline);
        }

        List<Cloudlet> finished = broker.getCloudletFinishedList();
        double makespan = 0, response = 0, waiting = 0;
        List<Double> responses = new ArrayList<>();
        for (Cloudlet c : finished) {
            double arrival = c.getSubmissionDelay();
            double finish = c.getFinishTime();
            double start = c.getExecStartTime();
            makespan = Math.max(makespan, finish);
            response += finish - arrival;
            waiting += start - arrival;
            responses.add(finish - arrival);
        }
        int done = finished.size();
        responses.sort(Double::compare);
        double p95Response = done == 0 ? 0 : responses.get(Math.min(done - 1, (int) Math.ceil(0.95 * done) - 1));

        Summary s = new Summary(
                mode.toString(), totalCloudlets, done, makespan,
                done == 0 ? 0 : response / done,
                done == 0 ? 0 : waiting / done,
                p95Response,
                makespan > 0 ? done / makespan : 0,
                lastTick[0] > 0 ? vmSeconds[0] / lastTick[0] : BASELINE_VMS,
                vmSeconds[0],
                scaleOut[0], scaleIn[0], flaps[0],
                cpuSamples[0] == 0 ? 0 : cpuSum[0] / cpuSamples[0],
                cpuSamples[0] == 0 ? 0 : 100.0 * highTicks[0] / cpuSamples[0],
                lostOnDestroy[0]);

        System.out.printf(Locale.US,
                "[trace] %-18s rep=%d cloudlets=%d/%d makespan=%.1f avgResp=%.2f avgVMs=%.1f scaleOut=%d scaleIn=%d flaps=%d%n",
                mode, repetition, done, totalCloudlets, s.makespan(), s.avgResponse(), s.avgVms(),
                s.scaleOut(), s.scaleIn(), s.flaps());
        if (done < totalCloudlets || lostOnDestroy[0] > 0) {
            System.out.printf(Locale.US,
                    "[trace] WARNING %s rep=%d: only %d of %d cloudlets finished (%d lost when a VM was removed). "
                            + "Results of this run are NOT valid.%n",
                    mode, repetition, done, totalCloudlets, lostOnDestroy[0]);
        }
        return s;
    }

    private Summary average(List<Summary> runs) {
        int n = runs.size();
        return new Summary(
                runs.get(0).mode(),
                runs.get(0).cloudlets(),
                (int) Math.round(runs.stream().mapToInt(Summary::completed).average().orElse(0)),
                runs.stream().mapToDouble(Summary::makespan).average().orElse(0),
                runs.stream().mapToDouble(Summary::avgResponse).average().orElse(0),
                runs.stream().mapToDouble(Summary::avgWaiting).average().orElse(0),
                runs.stream().mapToDouble(Summary::p95Response).average().orElse(0),
                runs.stream().mapToDouble(Summary::throughput).average().orElse(0),
                runs.stream().mapToDouble(Summary::avgVms).average().orElse(0),
                runs.stream().mapToDouble(Summary::vmSeconds).average().orElse(0),
                (int) Math.round(runs.stream().mapToInt(Summary::scaleOut).sum() / (double) n),
                (int) Math.round(runs.stream().mapToInt(Summary::scaleIn).sum() / (double) n),
                (int) Math.round(runs.stream().mapToInt(Summary::flaps).sum() / (double) n),
                runs.stream().mapToDouble(Summary::avgCpu).average().orElse(0),
                runs.stream().mapToDouble(Summary::highCpuShare).average().orElse(0),
                runs.stream().mapToInt(Summary::lost).sum());
    }

    private void writeTraceUsed(Path file, BitbrainsTraceLoader.Trace trace, double[] offeredLoad) {
        StringBuilder sb = new StringBuilder("step,start_time_s,active_vms,trace_cpu_percent,offered_load_percent\n");
        for (int i = 0; i < trace.steps(); i++) {
            sb.append(String.format(Locale.US, "%d,%.1f,%d,%.4f,%.4f%n",
                    i, i * STEP_SECONDS, trace.activeVms()[i], trace.cpuPercent()[i], offeredLoad[i] * 100.0));
        }
        write(file, sb.toString());
    }

    private void writeSummary(Path csv, Path md, List<Summary> summaries,
                              BitbrainsTraceLoader.Trace trace, double p95, double scale) {
        StringBuilder c = new StringBuilder("mode,cloudlets,completed,makespan,avg_response,avg_waiting,p95_response,"
                + "throughput_per_s,avg_vms,vm_seconds,scale_out,scale_in,flaps,avg_cpu_percent,share_ticks_above_70\n");
        for (Summary s : summaries) {
            c.append(String.format(Locale.US, "%s,%d,%d,%.4f,%.4f,%.4f,%.4f,%.6f,%.3f,%.2f,%d,%d,%d,%.3f,%.3f%n",
                    s.mode(), s.cloudlets(), s.completed(), s.makespan(), s.avgResponse(), s.avgWaiting(),
                    s.p95Response(), s.throughput(), s.avgVms(), s.vmSeconds(), s.scaleOut(), s.scaleIn(),
                    s.flaps(), s.avgCpu(), s.highCpuShare()));
        }
        write(csv, c.toString());

        StringBuilder m = new StringBuilder();
        m.append("# Bitbrains trace experiment\n\n");
        m.append(String.format(Locale.US,
                "Trace: %d VM files, %d steps used (start %d), mean CPU %.2f%%, P95 %.2f%%. "
                        + "Load scale x%.2f so that P95 equals %.0f%% of the capacity of %d baseline VMs "
                        + "(%d MIPS). One trace step = %.0f simulated seconds. Cloudlet length %d MI.%n%n",
                trace.vmFiles(), trace.steps(), WINDOW_START, trace.mean(), p95, scale,
                TARGET_P95_LOAD * 100.0, BASELINE_VMS, MIPS, STEP_SECONDS, CLOUDLET_LENGTH));
        m.append("Times are CloudSim seconds. Response and waiting time are measured from each cloudlet's arrival. "
                + "Throughput here is completed cloudlets divided by makespan (not the paper's Eq. 12). "
                + "Flaps = scaling direction reversals. S_THRESHOLD_SARSA is the mean of "
                + SARSA_REPETITIONS + " runs sharing one Q-table.\n\n");
        m.append("| Mode | Completed | Makespan | Avg response | Avg waiting | P95 response | Throughput/s | Avg VMs "
                + "| VM-seconds | Out | In | Flaps | % ticks CPU>70 |\n");
        m.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (Summary s : summaries) {
            m.append(String.format(Locale.US,
                    "| %s | %d/%d | %.1f | %.2f | %.2f | %.2f | %.3f | %.1f | %.0f | %d | %d | %d | %.1f |%n",
                    s.mode(), s.completed(), s.cloudlets(), s.makespan(), s.avgResponse(), s.avgWaiting(),
                    s.p95Response(), s.throughput(), s.avgVms(), s.vmSeconds(), s.scaleOut(), s.scaleIn(),
                    s.flaps(), s.highCpuShare()));
        }
        write(md, m.toString());
    }

    private void printSummary(List<Summary> summaries) {
        System.out.printf("%n%-20s %-10s %-10s %-10s %-10s %-9s %-6s %-6s %-6s%n",
                "Mode", "Makespan", "AvgResp", "P95Resp", "AvgVMs", "VM-sec", "Out", "In", "Flaps");
        for (Summary s : summaries) {
            System.out.printf(Locale.US, "%-20s %-10.1f %-10.2f %-10.2f %-10.1f %-9.0f %-6d %-6d %-6d%n",
                    s.mode(), s.makespan(), s.avgResponse(), s.p95Response(), s.avgVms(), s.vmSeconds(),
                    s.scaleOut(), s.scaleIn(), s.flaps());
        }
    }

    private void write(Path file, String content) {
        try {
            Files.writeString(file, content);
        } catch (IOException e) {
            throw new RuntimeException("Unable to write " + file, e);
        }
    }

    private record Summary(
            String mode,
            int cloudlets,
            int completed,
            double makespan,
            double avgResponse,
            double avgWaiting,
            double p95Response,
            double throughput,
            double avgVms,
            double vmSeconds,
            int scaleOut,
            int scaleIn,
            int flaps,
            double avgCpu,
            double highCpuShare,
            int lost) {}
}
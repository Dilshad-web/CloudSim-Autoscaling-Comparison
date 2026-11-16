package org.example;
import org.cloudbus.cloudsim.brokers.DatacenterBrokerSimple;
import org.cloudbus.cloudsim.cloudlets.Cloudlet;
import org.cloudbus.cloudsim.vms.Vm;
import org.example.simulation.BitbrainsTraceLoader;
import org.example.simulation.CloudEnvironment;
import org.example.simulation.CloudletManager;
import org.example.simulation.QThresholdManager;
import org.example.simulation.QThresholdRewardManager;
import org.example.simulation.ScalingAction;
import org.example.simulation.VmManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Trace-driven 30-day Q-Threshold experiment compatible with the S-Threshold batch setup.
 *
 * Uses the same raw Bitbrains fastStorage/2013-8 source folder, global P95 normalization,
 * 288 trace steps per day, 10-second trace step, 10 baseline VMs, 750 MIPS, 1000 MI
 * cloudlets, and three repeated runs per day as the S-Threshold trace batch runner.
 * The Q-Threshold Q-tables and learned thresholds persist across repetitions and days.
 *
 * This is a trace-driven engineering comparison, not a claim that the implementation
 * exactly reproduces the original Q-Threshold research algorithm.
 */
public final class QThresholdTraceBatchRunner {
    private static final Path RESULTS = Path.of(
            System.getProperty("qthreshold.resultsDir", "results_qthreshold")
    );
    private static final String DATA_DIR = System.getProperty(
            "bitbrains.dir", "data/bitbrains/fastStorage/2013-8");
    private static final int MAX_VM_FILES = Integer.getInteger("bitbrains.vms", 0);
    private static final int STEPS_PER_DAY = Integer.getInteger("bitbrains.stepsPerDay", 288);
    private static final int DAYS_LIMIT = Integer.getInteger("bitbrains.days", Integer.MAX_VALUE);
    private static final int BASELINE_VMS = 10;
    private static final long MIPS = 750;
    private static final long CLOUDLET_LENGTH = 1000;
    private static final double STEP_SECONDS = Double.parseDouble(
            System.getProperty("bitbrains.stepSeconds", "10"));
    private static final double TARGET_P95_LOAD = Double.parseDouble(
            System.getProperty("bitbrains.p95Load", "0.9"));
    private static final int REPETITIONS = 3;
    private static final double DECISION_INTERVAL = 2.0;
    // Stability safeguards for the trace-driven controller. These are explicit
    // controller parameters (not changes to the Bitbrains workload or host model).
    private static final double THRESHOLD_ADJUSTMENT_INTERVAL = 30.0;
    private static final double CPU_EMA_ALPHA = 0.35;
    private static final int SCALE_OUT_CONFIRMATION_SAMPLES = 2;
    private static final int SCALE_IN_CONFIRMATION_SAMPLES = 4;
    private static final double MIN_ACTION_GAP_SECONDS = 10.0;
    private static final double SCALE_IN_HOLD_AFTER_SCALE_OUT_SECONDS = 45.0;
    private static final double SCALE_OUT_RETRY_BACKOFF_SECONDS = 45.0;

    private final CloudletManager cloudletManager = new CloudletManager();
    private final VmManager vmManager = new VmManager();
    private final QThresholdRewardManager rewardManager = new QThresholdRewardManager();
    private final QThresholdManager qManager = new QThresholdManager();

    public static void main(String[] args) throws Exception {
        new QThresholdTraceBatchRunner().run();
    }

    private void run() throws IOException {
        boolean fresh = Boolean.getBoolean("bitbrains.fresh");
        prepareResults(fresh);
        Path dataPath = Path.of(DATA_DIR);
        if (!BitbrainsTraceLoader.isAvailable(dataPath)) {
            throw new IllegalStateException("No raw Bitbrains CSV files found at " + dataPath.toAbsolutePath());
        }

        int filesOnDisk = countCsvFiles(dataPath);
        if (MAX_VM_FILES != 0 && MAX_VM_FILES < filesOnDisk) {
            throw new IllegalStateException("For a full comparison use all raw files: -Dbitbrains.vms=0. Found " + filesOnDisk);
        }
        BitbrainsTraceLoader.Trace full = loadTrace(dataPath, MAX_VM_FILES, filesOnDisk);
        if (MAX_VM_FILES == 0 && full.vmFiles() != filesOnDisk) {
            throw new IllegalStateException("Expected all " + filesOnDisk + " files, loaded " + full.vmFiles());
        }

        int daysAvailable = full.steps() / STEPS_PER_DAY;
        int usedSteps = daysAvailable * STEPS_PER_DAY;
        if (daysAvailable < 1) throw new IllegalStateException("Trace is shorter than one full day.");
        BitbrainsTraceLoader.Trace used = full.window(0, usedSteps);
        double p95 = used.percentile(0.95);
        if (!(p95 > 0)) throw new IllegalStateException("Cannot normalize trace because global P95 CPU is zero.");
        double scale = TARGET_P95_LOAD * 100.0 / p95;
        double[] offeredAll = new double[usedSteps];
        for (int i = 0; i < usedSteps; i++) offeredAll[i] = used.cpuPercent()[i] / 100.0 * scale;
        int daysToRun = Math.min(daysAvailable, DAYS_LIMIT);

        String settings = "data=" + dataPath + ";files=" + full.vmFiles() + ";stepsPerDay=" + STEPS_PER_DAY
                + ";steps=" + usedSteps + ";globalP95=" + String.format(Locale.US, "%.10f", p95)
                + ";scale=" + String.format(Locale.US, "%.10f", scale) + ";targetP95Load=" + TARGET_P95_LOAD
                + ";baselineVms=" + BASELINE_VMS + ";mips=" + MIPS + ";cloudletLength=" + CLOUDLET_LENGTH
                + ";stepSeconds=" + STEP_SECONDS + ";decisionInterval=" + DECISION_INTERVAL
                + ";repetitions=" + REPETITIONS + ";alpha=0.1;gamma=0.9;epsilon=0.1"
                + ";controller=Q-Threshold-Stabilized-v2"
                + ";thresholdAdjustmentInterval=" + THRESHOLD_ADJUSTMENT_INTERVAL
                + ";cpuEmaAlpha=" + CPU_EMA_ALPHA
                + ";scaleOutConfirmSamples=" + SCALE_OUT_CONFIRMATION_SAMPLES
                + ";scaleInConfirmSamples=" + SCALE_IN_CONFIRMATION_SAMPLES
                + ";minimumActionGap=" + MIN_ACTION_GAP_SECONDS
                + ";scaleInHoldAfterScaleOut=" + SCALE_IN_HOLD_AFTER_SCALE_OUT_SECONDS;
        String signatureFile = settings + "\n";
        Path signaturePath = RESULTS.resolve("qthreshold_settings.signature");
        if (!fresh && Files.exists(signaturePath)) {
            String oldSignature = Files.readString(signaturePath, StandardCharsets.UTF_8);
            if (!oldSignature.equals(signatureFile)) {
                throw new IllegalStateException("Existing Q-Threshold results use different settings. Run with -Dbitbrains.fresh=true to archive them and start over.");
            }
        } else if (!fresh && Files.exists(RESULTS.resolve("qthreshold_30day_results.csv"))) {
            throw new IllegalStateException("Existing results have no settings signature. Run with -Dbitbrains.fresh=true to archive them.");
        }
        Files.writeString(signaturePath, signatureFile, StandardCharsets.UTF_8);
        Files.writeString(RESULTS.resolve("qthreshold_batch_config.md"),
                "# Q-Threshold raw Bitbrains experiment\n\n"
                        + "Uses the same raw trace directory and global normalization policy as the S-Threshold batch runner.\n\n"
                        + "- Simulated days available: " + daysAvailable + " (this run's day limit: " + daysToRun + ")\n"
                        + "- Steps per day: " + STEPS_PER_DAY + "\n"
                        + "- Trailing endpoint samples excluded: " + (full.steps() - usedSteps) + "\n"
                        + "- Repetitions per day: " + REPETITIONS + "\n"
                        + "- Q-table and threshold state persist across repetitions and days; state is saved after each day for resuming.\n"
                        + "- CPU trace is transformed to staggered synthetic cloudlet arrivals; it is not a direct replay of per-VM CPU scheduling.\n"
                        + "- Controller stabilization: EMA CPU signal, queue-aware scale-out, 2 consecutive scale-out samples, 4 consecutive scale-in samples, 30-second threshold adjustment interval, 10-second minimum action gap, and a 45-second scale-in hold after confirmed scale-out.\n"
                        + "- Reward is the existing project-level Q-Threshold reward based on response time and VM count; it is not claimed to be the original paper's exact reward.\n\n"
                        + "Settings signature:\n\n```\n" + settings + "\n```\n", StandardCharsets.UTF_8);
        writeTraceUsed(used, offeredAll);

        Path allRuns = RESULTS.resolve("qthreshold_30day_results.csv");
        Path daySummary = RESULTS.resolve("qthreshold_batch_summary.csv");
        int completedDays = findCompletedDays(daysAvailable);
        if (completedDays > 0) {
            Path state = RESULTS.resolve(String.format(Locale.US, "qthreshold_state_day%02d.csv", completedDays));
            if (!Files.exists(state)) throw new IllegalStateException("Missing saved controller state for completed day " + completedDays + ". Start fresh to avoid an inconsistent continuation.");
            qManager.loadState(state);
            System.out.println("Resuming from day " + (completedDays + 1) + " using controller state saved after day " + completedDays + ".");
        }
        rebuildAggregateFiles(completedDays, allRuns, daySummary);

        System.out.printf(Locale.US, "Raw files=%d, trace steps=%d, simulated days=%d, P95=%.4f%%, scale=%.6f, target P95=%.1f%%\n",
                full.vmFiles(), full.steps(), daysAvailable, p95, scale, TARGET_P95_LOAD * 100.0);
        System.out.println("Starting/resuming Q-Threshold run. Results: " + RESULTS.toAbsolutePath());

        int validDays = countValidDays(completedDays);
        for (int day = completedDays + 1; day <= daysToRun; day++) {
            int from = (day - 1) * STEPS_PER_DAY;
            BitbrainsTraceLoader.Trace dayTrace = used.window(from, STEPS_PER_DAY);
            double[] offered = Arrays.copyOfRange(offeredAll, from, from + STEPS_PER_DAY);
            List<RunResult> dayRuns = new ArrayList<>();
            boolean dayValid = true;
            QThresholdManager.Snapshot qBeforeDay = qManager.snapshot();
            for (int rep = 1; rep <= REPETITIONS; rep++) {
                System.out.printf(Locale.US, "[Q-Threshold] day %02d/%02d repetition %d/%d%n", day, daysAvailable, rep, REPETITIONS);
                RunResult result = runOne(day, rep, dayTrace, offered);
                dayRuns.add(result);
                if (!result.valid()) dayValid = false;
                append(allRuns, result.toCsv(day));
            }
            String status = dayValid ? "VALID" : "INVALID";
            if (dayValid) {
                validDays++;
            } else {
                // Match the S-Threshold batch policy: an invalid day must not teach the controller.
                qManager.restore(qBeforeDay);
            }
            qManager.saveState(RESULTS.resolve(String.format(Locale.US, "qthreshold_state_day%02d.csv", day)));
            writeDayFile(day, status, dayRuns);
            append(daySummary, summarizeDay(day, status, dayRuns));
            System.out.printf("[Q-Threshold] day %02d %s; valid days=%d/%d%n", day, status, validDays, daysToRun);
        }
        writeOverallSummary(daySummary);
        System.out.printf("Finished %d days; valid=%d, invalid=%d. Day summary: %s%n",
                daysToRun, validDays, daysToRun - validDays, daySummary.toAbsolutePath());
        System.out.println("Overall summary: " + RESULTS.resolve("qthreshold_overall_summary.md").toAbsolutePath());
    }

    private RunResult runOne(int day, int repetition, BitbrainsTraceLoader.Trace trace, double[] offeredLoad) {
        qManager.setRandomSeed(42L + day * 10_000L + repetition);
        CloudEnvironment environment = new CloudEnvironment();
        List<Vm> initialVms = vmManager.createVms(BASELINE_VMS, MIPS);
        List<Cloudlet> cloudlets = cloudletManager.createTraceCloudlets(
                offeredLoad, BASELINE_VMS, MIPS, CLOUDLET_LENGTH, STEP_SECONDS);
        List<Cloudlet> ordered = new ArrayList<>(cloudlets);
        int requested = cloudlets.size();
        DatacenterBrokerSimple broker = new DatacenterBrokerSimple(environment.getSimulation());

        // Do not allow an unplaceable VM to generate an unbounded broker retry event loop.
        broker.setFailedVmsRetryDelay(0);
        broker.setVmDestructionDelay(trace.steps() * STEP_SECONDS + 600.0);
        broker.submitVmList(initialVms);
        broker.submitCloudletList(cloudlets);

        double[] lastTick = {0.0};
        double[] lastDecision = {0.0};
        double[] lastThresholdAdjustment = {0.0};
        double[] vmSeconds = {0.0};
        double[] cpuSum = {0.0};
        double[] smoothedCpu = {Double.NaN};
        int[] cpuSamples = {0};
        int[] scaleOut = {0};       // confirmed allocations only
        int[] scaleIn = {0};        // confirmed destructions only
        int[] scaleOutRequests = {0};
        int[] flaps = {0};
        int[] lostOnDestroy = {0};
        int[] firstPending = {0};
        int[] firstUnfinished = {0};
        int[] roundRobin = {0};
        int[] consecutiveHigh = {0};
        int[] consecutiveLow = {0};
        int[] maxQueueBacklog = {0};
        int[] scaleOutFailures = {0};
        double[] lastConfirmedActionAt = {-1.0e9};
        double[] nextScaleInAllowedAt = {0.0};
        double[] nextScaleOutAllowedAt = {0.0};
        ScalingAction[] lastApplied = {null};
        List<Vm> pendingScaleOut = new ArrayList<>();
        List<Vm> pendingScaleIn = new ArrayList<>();
        List<Transition> transitions = new ArrayList<>();

        environment.getSimulation().addOnClockTickListener(info -> {
            double now = info.getTime();
            if (broker.getCloudletFinishedList().size() >= requested) return;

            List<Vm> execList = broker.getVmExecList();
            if (execList.isEmpty()) return;

            double dt = Math.max(0.0, now - lastTick[0]);
            lastTick[0] = now;
            vmSeconds[0] += execList.size() * dt;
            if (now - lastDecision[0] < DECISION_INTERVAL) return;
            lastDecision[0] = now;

            int before = execList.size();

            // Determine which cloudlets have arrived and remain unfinished.
            while (firstPending[0] < ordered.size()
                    && ordered.get(firstPending[0]).getSubmissionDelay() <= now) {
                firstPending[0]++;
            }
            while (firstUnfinished[0] < firstPending[0]
                    && ordered.get(firstUnfinished[0]).isFinished()) {
                firstUnfinished[0]++;
            }

            Set<Vm> inUse = new HashSet<>();
            int outstandingSubmitted = 0;
            for (int i = firstUnfinished[0]; i < firstPending[0]; i++) {
                Cloudlet cloudlet = ordered.get(i);
                if (!cloudlet.isFinished()) {
                    outstandingSubmitted++;
                    if (cloudlet.getVm() != null) inUse.add(cloudlet.getVm());
                }
            }
            // One PE is configured per VM with a space-shared scheduler. Outstanding
            // submitted cloudlets beyond active VM capacity represent queued work.
            int queueBacklog = Math.max(0, outstandingSubmitted - before);
            maxQueueBacklog[0] = Math.max(maxQueueBacklog[0], queueBacklog);

            double cpuPercent = execList.stream()
                    .mapToDouble(Vm::getCpuPercentUtilization)
                    .average().orElse(0.0) * 100.0;
            cpuPercent = Math.max(0.0, Math.min(100.0, cpuPercent));
            cpuSum[0] += cpuPercent;
            cpuSamples[0]++;
            if (!Double.isFinite(smoothedCpu[0])) {
                smoothedCpu[0] = cpuPercent;
            } else {
                smoothedCpu[0] = CPU_EMA_ALPHA * cpuPercent
                        + (1.0 - CPU_EMA_ALPHA) * smoothedCpu[0];
            }

            // Threshold learning is deliberately slower than the 2-second scaling
            // observation loop. This stops +/-2% actions from moving thresholds on
            // every CPU sample while retaining the same Q-tables and action set.
            int lowerAdjustment = 0;
            int upperAdjustment = 0;
            boolean thresholdAdjusted = now - lastThresholdAdjustment[0]
                    >= THRESHOLD_ADJUSTMENT_INTERVAL;
            if (thresholdAdjusted) {
                lowerAdjustment = qManager.chooseLowerThresholdAdjustment(before);
                upperAdjustment = qManager.chooseUpperThresholdAdjustment(before);
                qManager.adjustThresholds(lowerAdjustment, upperAdjustment);
                lastThresholdAdjustment[0] = now;
            }

            ScalingAction thresholdAction = qManager.decideScalingAction(smoothedCpu[0]);
            // Any real queue is an additional scale-out signal. This protects against
            // an average-CPU-only controller scaling in while SpaceShared VMs queue work.
            boolean highSignal = thresholdAction == ScalingAction.SCALE_OUT || queueBacklog > 0;
            boolean lowSignal = thresholdAction == ScalingAction.SCALE_IN
                    && queueBacklog == 0
                    && outstandingSubmitted <= before;
            consecutiveHigh[0] = highSignal ? consecutiveHigh[0] + 1 : 0;
            consecutiveLow[0] = lowSignal ? consecutiveLow[0] + 1 : 0;

            boolean scaleInConfirmedThisTick = false;
            boolean scaleOutConfirmedThisTick = false;

            // Resolve pending scale-in requests first. A scale-in is counted only
            // after the broker's active VM list confirms that the VM has disappeared.
            for (Vm pendingVm : new ArrayList<>(pendingScaleIn)) {
                if (!execList.contains(pendingVm)) {
                    scaleIn[0]++;
                    pendingScaleIn.remove(pendingVm);
                    scaleInConfirmedThisTick = true;
                    lastConfirmedActionAt[0] = now;
                    nextScaleOutAllowedAt[0] = Math.max(
                            nextScaleOutAllowedAt[0], now + MIN_ACTION_GAP_SECONDS);
                }
            }

            // Resolve pending scale-out requests. Failed requests are recorded and
            // backed off; they are not silently forgotten or retried every tick.
            for (Vm pendingVm : new ArrayList<>(pendingScaleOut)) {
                if (execList.contains(pendingVm)) {
                    scaleOut[0]++;
                    pendingScaleOut.remove(pendingVm);
                    scaleOutConfirmedThisTick = true;
                    lastConfirmedActionAt[0] = now;
                    nextScaleInAllowedAt[0] = Math.max(
                            nextScaleInAllowedAt[0],
                            now + SCALE_IN_HOLD_AFTER_SCALE_OUT_SECONDS);
                } else if (broker.getVmFailedList().contains(pendingVm)) {
                    pendingScaleOut.remove(pendingVm);
                    scaleOutFailures[0]++;
                    nextScaleOutAllowedAt[0] = Math.max(
                            nextScaleOutAllowedAt[0], now + SCALE_OUT_RETRY_BACKOFF_SECONDS);
                    consecutiveHigh[0] = 0;
                    System.err.printf(Locale.US,
                            "[Q-Threshold] VM allocation failure at %.2fs: vmId=%d activeVms=%d queueBacklog=%d; retry backoff %.0fs%n",
                            now, pendingVm.getId(), execList.size(), queueBacklog,
                            SCALE_OUT_RETRY_BACKOFF_SECONDS);
                }
            }

            ScalingAction appliedAction = ScalingAction.NO_ACTION;
            if (scaleOutConfirmedThisTick) {
                appliedAction = ScalingAction.SCALE_OUT;
            } else if (scaleInConfirmedThisTick) {
                appliedAction = ScalingAction.SCALE_IN;
            }

            if (appliedAction != ScalingAction.NO_ACTION) {
                if (lastApplied[0] != null && lastApplied[0] != appliedAction) {
                    flaps[0]++;
                }
                lastApplied[0] = appliedAction;
            }

            boolean scaleOutReady = consecutiveHigh[0] >= SCALE_OUT_CONFIRMATION_SAMPLES
                    || queueBacklog >= Math.max(2, before / 2);
            boolean scaleInReady = consecutiveLow[0] >= SCALE_IN_CONFIRMATION_SAMPLES;
            Vm removeCandidate = null;

            // Only one scaling request may be outstanding. A confirmed scale-out
            // creates a 45-second scale-in hold; a failed placement has a backoff.
            boolean noPendingVmOperation = pendingScaleOut.isEmpty() && pendingScaleIn.isEmpty();
            boolean actionGapPassed = now - lastConfirmedActionAt[0] >= MIN_ACTION_GAP_SECONDS;

            if (!scaleOutConfirmedThisTick && !scaleInConfirmedThisTick
                    && noPendingVmOperation && before < VmManager.MAX_VMS
                    && scaleOutReady && now >= nextScaleOutAllowedAt[0]) {
                Vm newVm = vmManager.createVms(1, MIPS).get(0);
                pendingScaleOut.add(newVm);
                scaleOutRequests[0]++;
                broker.submitVm(newVm);
                System.out.printf(Locale.US,
                        "[Q-Threshold] SCALE-OUT submitted: t=%.2f active=%d backlog=%d smoothCPU=%.1f%% lower=%.1f upper=%.1f%n",
                        now, before, queueBacklog, smoothedCpu[0],
                        qManager.getLowerThreshold(), qManager.getUpperThreshold());
                consecutiveHigh[0] = 0;
            } else if (!scaleOutConfirmedThisTick && !scaleInConfirmedThisTick
                    && noPendingVmOperation && before > VmManager.MIN_VMS
                    && scaleInReady && actionGapPassed && now >= nextScaleInAllowedAt[0]) {
                // Scale-in is permitted only when there is no queued work and an
                // actually idle VM can be removed without evicting assigned work.
                removeCandidate = execList.stream()
                        .filter(vm -> !inUse.contains(vm))
                        .findFirst().orElse(null);
                if (removeCandidate != null) {
                    List<Vm> targets = new ArrayList<>(execList);
                    targets.remove(removeCandidate);
                    if (!targets.isEmpty()) {
                        for (int i = firstPending[0]; i < ordered.size(); i++) {
                            ordered.get(i).setVm(targets.get(roundRobin[0]++ % targets.size()));
                        }
                    }
                    lostOnDestroy[0] += broker.destroyVm(removeCandidate).size();
                    pendingScaleIn.add(removeCandidate);
                    consecutiveLow[0] = 0;
                    System.out.printf(Locale.US,
                            "[Q-Threshold] SCALE-IN submitted: t=%.2f active=%d backlog=%d smoothCPU=%.1f%% lower=%.1f upper=%.1f%n",
                            now, before, queueBacklog, smoothedCpu[0],
                            qManager.getLowerThreshold(), qManager.getUpperThreshold());
                }
            }

            // Keep future arrivals assigned only to VMs that are active and not
            // currently being destroyed. This preserves the runner's round-robin
            // future-cloudlet assignment behavior.
            if (removeCandidate == null) {
                List<Vm> targets = new ArrayList<>(execList);
                if (!targets.isEmpty()) {
                    for (int i = firstPending[0]; i < ordered.size(); i++) {
                        ordered.get(i).setVm(targets.get(roundRobin[0]++ % targets.size()));
                    }
                }
            }

            if (thresholdAdjusted) {
                transitions.add(new Transition(
                        before, lowerAdjustment, upperAdjustment,
                        broker.getVmExecList().size()));
            }
        });

        environment.getSimulation().start();
        List<Vm> activeAtEnd = new ArrayList<>(broker.getVmExecList());
        List<Vm> createdAtEnd = new ArrayList<>(broker.getVmCreatedList());
        // Resolve scale operations that completed during the final shutdown events.
        // getVmCreatedList retains VMs that were successfully created and later destroyed.
        for (Vm pendingVm : new ArrayList<>(pendingScaleIn)) {
            if (!activeAtEnd.contains(pendingVm)) {
                scaleIn[0]++;
                pendingScaleIn.remove(pendingVm);
            }
        }
        for (Vm pendingVm : new ArrayList<>(pendingScaleOut)) {
            if (createdAtEnd.contains(pendingVm)) {
                scaleOut[0]++;
                pendingScaleOut.remove(pendingVm);
            } else if (broker.getVmFailedList().contains(pendingVm)) {
                scaleOutFailures[0]++;
                pendingScaleOut.remove(pendingVm);
            }
        }

        List<Cloudlet> finished = broker.getCloudletFinishedList();
        int completed = finished.size();
        double makespan = 0.0;
        double response = 0.0;
        double waiting = 0.0;
        double turnaround = 0.0;
        List<Double> responseTimes = new ArrayList<>();
        for (Cloudlet cloudlet : finished) {
            double arrival = cloudlet.getSubmissionDelay();
            double start = cloudlet.getExecStartTime();
            double finish = cloudlet.getFinishTime();
            if (!Double.isFinite(arrival) || !Double.isFinite(start)
                    || !Double.isFinite(finish) || finish < arrival || start < arrival) {
                continue;
            }
            makespan = Math.max(makespan, finish);
            response += finish - arrival;
            waiting += start - arrival;
            turnaround += finish - arrival;
            responseTimes.add(finish - arrival);
        }
        responseTimes.sort(Comparator.naturalOrder());
        double avgResponse = completed == 0 ? 0.0 : response / completed;
        double avgWaiting = completed == 0 ? 0.0 : waiting / completed;
        double avgTurnaround = completed == 0 ? 0.0 : turnaround / completed;
        double p95Response = responseTimes.isEmpty() ? 0.0 : responseTimes.get(Math.min(
                responseTimes.size() - 1,
                (int) Math.ceil(0.95 * responseTimes.size()) - 1));
        double throughput = makespan > 0.0 ? completed / makespan : 0.0;
        double avgVms = lastTick[0] > 0.0 ? vmSeconds[0] / lastTick[0] : BASELINE_VMS;
        int failedVmCount = broker.getVmFailedList().size();
        int invalidMetricCloudlets = completed - responseTimes.size();
        int unresolvedScaleOuts = (int) pendingScaleOut.stream()
                .filter(vm -> !broker.getVmFailedList().contains(vm)
                        && !createdAtEnd.contains(vm)).count();
        int unresolvedScaleIns = (int) pendingScaleIn.stream()
                .filter(activeAtEnd::contains).count();

        List<String> validationReasons = new ArrayList<>();
        if (requested <= 0) validationReasons.add("NO_CLOUDLETS_REQUESTED");
        if (completed != requested) validationReasons.add("CLOUDLET_COMPLETION_MISMATCH");
        if (invalidMetricCloudlets != 0) validationReasons.add("INVALID_CLOUDLET_TIMESTAMPS");
        if (lostOnDestroy[0] != 0) validationReasons.add("CLOUDLETS_RETURNED_DURING_VM_DESTROY");
        if (failedVmCount != 0) validationReasons.add("FAILED_VM_REQUESTS=" + failedVmCount);
        if (scaleOutFailures[0] != 0) validationReasons.add("SCALE_OUT_FAILURES=" + scaleOutFailures[0]);
        if (unresolvedScaleOuts != 0) validationReasons.add("UNRESOLVED_SCALE_OUTS=" + unresolvedScaleOuts);
        if (unresolvedScaleIns != 0) validationReasons.add("UNRESOLVED_SCALE_INS=" + unresolvedScaleIns);
        if (!Double.isFinite(makespan) || makespan <= 0.0) validationReasons.add("INVALID_MAKESPAN");
        if (!Double.isFinite(avgResponse) || !Double.isFinite(avgWaiting)) validationReasons.add("NON_FINITE_METRICS");

        boolean valid = validationReasons.isEmpty();
        // Use measured average VM count for the resource penalty. The broker may
        // destroy all VMs during shutdown; the created/active count at shutdown is
        // not the resource consumption of the run.
        double reward = valid
                ? rewardManager.calculateReward(avgResponse, (int) Math.round(avgVms), VmManager.MAX_VMS)
                : -2.0;
        if (valid) {
            for (Transition transition : transitions) {
                qManager.learnFromReward(transition.vmBefore(), transition.lowerAdjustment(),
                        transition.upperAdjustment(), reward, transition.vmAfter());
            }
        }

        String reasonText = validationReasons.isEmpty() ? "NONE" : String.join("|", validationReasons);
        System.out.printf(Locale.US,
                "[Q-Threshold] Run checks: requested=%d completed=%d validMetricRows=%d failedVmRequests=%d scaleOutRequests=%d scaleOutFailures=%d lostOnDestroy=%d unresolvedScaleOuts=%d unresolvedScaleIns=%d maxQueueBacklog=%d reason=%s%n",
                requested, completed, responseTimes.size(), failedVmCount, scaleOutRequests[0],
                scaleOutFailures[0], lostOnDestroy[0], unresolvedScaleOuts, unresolvedScaleIns,
                maxQueueBacklog[0], reasonText);
        System.out.printf(Locale.US,
                "[Q-Threshold] Run summary: meanCPU=%.2f%% avgVMs=%.2f scaleOut=%d scaleIn=%d flaps=%d thresholds=%.1f/%.1f reward=%.6f%n",
                cpuSamples[0] == 0 ? 0.0 : cpuSum[0] / cpuSamples[0], avgVms,
                scaleOut[0], scaleIn[0], flaps[0], qManager.getLowerThreshold(),
                qManager.getUpperThreshold(), reward);

        return new RunResult(day, repetition, valid, requested, completed, lostOnDestroy[0], makespan,
                avgResponse, avgWaiting, avgTurnaround, p95Response, throughput, avgVms, vmSeconds[0],
                scaleOut[0], scaleIn[0], flaps[0], cpuSamples[0] == 0 ? 0.0 : cpuSum[0] / cpuSamples[0],
                qManager.getLowerThreshold(), qManager.getUpperThreshold(), reward,
                failedVmCount, scaleOutRequests[0], scaleOutFailures[0], invalidMetricCloudlets,
                unresolvedScaleOuts, unresolvedScaleIns, maxQueueBacklog[0], reasonText);
    }

    private void writeOverallSummary(Path daySummary) throws IOException {
        List<String> lines = Files.readAllLines(daySummary, StandardCharsets.UTF_8);
        List<String[]> valid = new ArrayList<>();
        int invalidDays = 0;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            String[] c = lines.get(i).split(",");
            if (c.length < 17) continue;
            if (c[1].equals("VALID")) valid.add(c); else invalidDays++;
        }
        int[] metricCols = {5, 6, 7, 8, 9, 10, 11, 12, 16};
        String[] names = {"Makespan", "Average response time", "Average waiting time", "Average turnaround time",
                "P95 response time", "Throughput (completed cloudlets / makespan)", "Average VM count",
                "VM-seconds", "Reward (custom Q-Threshold reward)"};
        StringBuilder md = new StringBuilder("# Q-Threshold 30-day overall summary\n\n")
                .append("- Valid days included: ").append(valid.size()).append("\n")
                .append("- Invalid days excluded from metric averages: ").append(invalidDays).append("\n")
                .append("- Repetitions per day: ").append(REPETITIONS).append("\n\n")
                .append("| Metric | Mean across valid days | Sample standard deviation |\n|---|---:|---:|\n");
        StringBuilder csv = new StringBuilder("metric,valid_days,mean,sample_stddev\n");
        for (int m = 0; m < metricCols.length; m++) {
            double[] values = new double[valid.size()];
            for (int i = 0; i < valid.size(); i++) values[i] = Double.parseDouble(valid.get(i)[metricCols[m]]);
            double mean = values.length == 0 ? 0 : Arrays.stream(values).average().orElse(0.0);
            double sum = 0;
            for (double v : values) sum += (v - mean) * (v - mean);
            double sd = values.length < 2 ? 0 : Math.sqrt(sum / (values.length - 1));
            md.append(String.format(Locale.US, "| %s | %.6f | %.6f |%n", names[m], mean, sd));
            csv.append(String.format(Locale.US, "\"%s\",%d,%.8f,%.8f%n", names[m], values.length, mean, sd));
        }
        md.append("\nMetrics use the same definitions as the S-Threshold trace runner for makespan, response time, waiting time, P95 response time, throughput, VM-seconds, and average VM count. Turnaround time is additionally reported as finish time minus arrival time.\n\n")
                .append("This summary is generated from the actual simulation outputs; no results are fabricated. A zero valid-day count means the experiment needs debugging before comparison.\n");
        Files.writeString(RESULTS.resolve("qthreshold_overall_summary.md"), md.toString(), StandardCharsets.UTF_8);
        Files.writeString(RESULTS.resolve("qthreshold_overall_summary.csv"), csv.toString(), StandardCharsets.UTF_8);
    }

    private void prepareResults(boolean fresh) throws IOException {
        if (Files.exists(RESULTS) && fresh) {
            Path archive = Path.of("results_qthreshold_archive_" + System.currentTimeMillis());
            Files.move(RESULTS, archive);
            System.out.println("Archived previous Q-Threshold results to " + archive.toAbsolutePath());
        }
        Files.createDirectories(RESULTS);
    }

    private int findCompletedDays(int daysAvailable) throws IOException {
        int completed = 0;
        for (int day = 1; day <= daysAvailable; day++) {
            Path dayFile = RESULTS.resolve(String.format(Locale.US, "qthreshold_day%02d.csv", day));
            if (!Files.exists(dayFile)) break;
            List<String> lines = Files.readAllLines(dayFile, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).startsWith("# status=")) break;
            completed = day;
        }
        for (int day = completed + 1; day <= daysAvailable; day++) {
            if (Files.exists(RESULTS.resolve(String.format(Locale.US, "qthreshold_day%02d.csv", day)))) {
                throw new IllegalStateException("A later day file exists after a missing day. Start fresh or restore the missing day file.");
            }
        }
        return completed;
    }

    private int countValidDays(int completedDays) throws IOException {
        int count = 0;
        for (int day = 1; day <= completedDays; day++) {
            List<String> lines = Files.readAllLines(RESULTS.resolve(String.format(Locale.US, "qthreshold_day%02d.csv", day)), StandardCharsets.UTF_8);
            if (!lines.isEmpty() && lines.get(0).equals("# status=VALID")) count++;
        }
        return count;
    }

    private void rebuildAggregateFiles(int completedDays, Path allRuns, Path daySummary) throws IOException {
        Files.writeString(allRuns, "day,repetition,status,cloudlets_requested,cloudlets_completed,cloudlets_lost,makespan,avg_response_time,avg_waiting_time,avg_turnaround_time,p95_response_time,throughput_cloudlets_per_s,avg_vms,vm_seconds,scale_out,scale_in,flaps,mean_sim_cpu_percent,lower_threshold_end,upper_threshold_end,reward,failed_vm_requests,scale_out_requests,scale_out_failures,invalid_metric_cloudlets,unresolved_scale_outs,unresolved_scale_ins,max_queue_backlog,validation_reason\n");
        Files.writeString(daySummary, "day,status,repetitions,mean_cloudlets_requested,mean_cloudlets_completed,mean_makespan,mean_response_time,mean_waiting_time,mean_turnaround_time,mean_p95_response_time,mean_throughput_cloudlets_per_s,mean_avg_vms,mean_vm_seconds,mean_scale_out,mean_scale_in,mean_flaps,mean_reward\n");
        for (int day = 1; day <= completedDays; day++) {
            Path file = RESULTS.resolve(String.format(Locale.US, "qthreshold_day%02d.csv", day));
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            String status = lines.get(0).equals("# status=VALID") ? "VALID" : "INVALID";
            List<RunResult> runs = new ArrayList<>();
            for (int i = 3; i < lines.size(); i++) {
                if (lines.get(i).isBlank()) continue;
                append(allRuns, lines.get(i) + "\n");
                runs.add(parseRunResult(lines.get(i)));
            }
            append(daySummary, summarizeDay(day, status, runs));
        }
    }

    private static RunResult parseRunResult(String line) {
        String[] c = line.split(",");
        return new RunResult(Integer.parseInt(c[0]), Integer.parseInt(c[1]), c[2].equals("VALID"),
                Integer.parseInt(c[3]), Integer.parseInt(c[4]), Integer.parseInt(c[5]),
                Double.parseDouble(c[6]), Double.parseDouble(c[7]), Double.parseDouble(c[8]),
                Double.parseDouble(c[9]), Double.parseDouble(c[10]), Double.parseDouble(c[11]),
                Double.parseDouble(c[12]), Double.parseDouble(c[13]), Integer.parseInt(c[14]),
                Integer.parseInt(c[15]), Integer.parseInt(c[16]), Double.parseDouble(c[17]),
                Double.parseDouble(c[18]), Double.parseDouble(c[19]), Double.parseDouble(c[20]),
                c.length > 21 ? Integer.parseInt(c[21]) : 0,
                c.length > 22 ? Integer.parseInt(c[22]) : 0,
                c.length > 23 ? Integer.parseInt(c[23]) : 0,
                c.length > 24 ? Integer.parseInt(c[24]) : 0,
                c.length > 25 ? Integer.parseInt(c[25]) : 0,
                c.length > 26 ? Integer.parseInt(c[26]) : 0,
                c.length > 27 ? Integer.parseInt(c[27]) : 0,
                c.length > 28 ? c[28] : "UNKNOWN");
    }

    private BitbrainsTraceLoader.Trace loadTrace(Path dataPath, int maxFiles, int expectedFiles) throws IOException {
        Path cache = RESULTS.resolve("bitbrains_trace_cache_files" + expectedFiles + "_vms" + maxFiles + ".csv");
        if (Files.exists(cache)) {
            try { return BitbrainsTraceLoader.loadCache(cache); }
            catch (RuntimeException | IOException e) { System.out.println("Trace cache unreadable; parsing raw CSV files again: " + e.getMessage()); }
        }
        System.out.println("Reading " + expectedFiles + " raw Bitbrains CSV files; first run can take several minutes...");
        BitbrainsTraceLoader.Trace trace = BitbrainsTraceLoader.load(dataPath, maxFiles);
        BitbrainsTraceLoader.saveCache(trace, cache);
        return trace;
    }

    private static int countCsvFiles(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir, 4)) {
            return (int)s.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv")).count();
        }
    }

    private void writeTraceUsed(BitbrainsTraceLoader.Trace trace, double[] offered) throws IOException {
        StringBuilder b = new StringBuilder("step,day,step_in_day,raw_cpu_percent,active_vms,offered_load_percent\n");
        for (int i = 0; i < offered.length; i++) {
            b.append(String.format(Locale.US, "%d,%d,%d,%.6f,%d,%.6f%n", i, i / STEPS_PER_DAY + 1,
                    i % STEPS_PER_DAY, trace.cpuPercent()[i], trace.activeVms()[i], offered[i] * 100.0));
        }
        Files.writeString(RESULTS.resolve("qthreshold_batch_trace_used.csv"), b.toString(), StandardCharsets.UTF_8);
    }

    private void writeDayFile(int day, String status, List<RunResult> runs) throws IOException {
        StringBuilder b = new StringBuilder("# status=" + status + "\n# day=" + day + "; repetitions=" + REPETITIONS + "\n");
        b.append("day,repetition,status,cloudlets_requested,cloudlets_completed,cloudlets_lost,makespan,avg_response_time,avg_waiting_time,avg_turnaround_time,p95_response_time,throughput_cloudlets_per_s,avg_vms,vm_seconds,scale_out,scale_in,flaps,mean_sim_cpu_percent,lower_threshold_end,upper_threshold_end,reward,failed_vm_requests,scale_out_requests,scale_out_failures,invalid_metric_cloudlets,unresolved_scale_outs,unresolved_scale_ins,max_queue_backlog,validation_reason\n");
        for (RunResult r : runs) b.append(r.toCsv(day));
        Files.writeString(RESULTS.resolve(String.format(Locale.US, "qthreshold_day%02d.csv", day)), b.toString());
    }

    private static String summarizeDay(int day, String status, List<RunResult> runs) {
        return String.format(Locale.US, "%d,%s,%d,%.3f,%.3f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.3f,%.3f,%.3f,%.8f%n",
                day, status, runs.size(), mean(runs, r -> r.requested()), mean(runs, r -> r.completed()), mean(runs, RunResult::makespan),
                mean(runs, RunResult::avgResponse), mean(runs, RunResult::avgWaiting), mean(runs, RunResult::avgTurnaround),
                mean(runs, RunResult::p95Response), mean(runs, RunResult::throughput), mean(runs, RunResult::avgVms),
                mean(runs, RunResult::vmSeconds), mean(runs, r -> r.scaleOut()),
                mean(runs, r -> r.scaleIn()), mean(runs, r -> r.flaps()), mean(runs, RunResult::reward));
    }

    private static double mean(List<RunResult> rows, java.util.function.ToDoubleFunction<RunResult> f) {
        return rows.stream().mapToDouble(f).average().orElse(0.0);
    }
    private static void append(Path file, String row) {
        try { Files.writeString(file, row, StandardOpenOption.APPEND); }
        catch (IOException e) { throw new RuntimeException("Unable to append " + file, e); }
    }

    private record Transition(int vmBefore, int lowerAdjustment, int upperAdjustment, int vmAfter) {}
    private record RunResult(int day, int repetition, boolean valid, int requested, int completed, int lost,
                             double makespan, double avgResponse, double avgWaiting, double avgTurnaround, double p95Response,
                             double throughput, double avgVms, double vmSeconds, int scaleOut, int scaleIn, int flaps,
                             double meanSimCpu, double lowerThreshold, double upperThreshold, double reward,
                             int failedVmRequests, int scaleOutRequests, int scaleOutFailures,
                             int invalidMetricCloudlets, int unresolvedScaleOuts, int unresolvedScaleIns,
                             int maxQueueBacklog, String validationReason) {
        String toCsv(int dayNo) {
            return String.format(Locale.US, "%d,%d,%s,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.8f,%.6f,%.6f,%d,%d,%d,%.6f,%.4f,%.4f,%.8f,%d,%d,%d,%d,%d,%d,%d,%s%n",
                    dayNo, repetition, valid ? "VALID" : "INVALID", requested, completed, lost, makespan,
                    avgResponse, avgWaiting, avgTurnaround, p95Response, throughput, avgVms, vmSeconds,
                    scaleOut, scaleIn, flaps, meanSimCpu, lowerThreshold, upperThreshold, reward,
                    failedVmRequests, scaleOutRequests, scaleOutFailures, invalidMetricCloudlets,
                    unresolvedScaleOuts, unresolvedScaleIns, maxQueueBacklog, validationReason);
        }
    }
}

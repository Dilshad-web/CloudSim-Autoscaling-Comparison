package org.example;

import org.example.simulation.BatchAggregator;
import org.example.simulation.BatchCheckpoint;
import org.example.simulation.BitbrainsTraceLoader;
import org.example.simulation.QTableStore;
import org.example.simulation.RunRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Final Bitbrains experiment: the full trace, one simulated day after the other.
 *
 * Rules (all enforced here):
 *  - ALL CSV files of the data folder are used (-Dbitbrains.vms=0); the number of loaded files is checked.
 *  - One simulated day = stepsPerDay trace steps (288). Days = floor(samples / 288); a trailing sample
 *    (the trace has 8641 samples = 30 days + 1 endpoint) is not simulated and not part of any statistic.
 *  - The load scale is computed ONCE from the whole simulated trace (P95 -> target share of the baseline
 *    capacity) and used for every day. It does not depend on -Dbitbrains.days.
 *  - Days run in order 1, 2, 3, ... because the SARSA Q-table is carried from day to day.
 *  - Every day uses the same offered-load sequence for STATIC, THRESHOLD_ONLY and all SARSA repetitions.
 *  - A day is VALID only if all runs finished all their cloudlets. An invalid day is logged, its SARSA
 *    learning is undone (Q-table restored), and it is left out of the final statistics.
 *  - After each day its files are saved (Q-table first, day file last). Starting again resumes at the
 *    first day without a day file and loads the Q-table of the last valid day.
 *
 * Options (JVM -D flags):
 *   -Dbitbrains.dir=data/bitbrains/fastStorage/2013-8   data folder
 *   -Dbitbrains.vms=0                                   0 = all files (anything else needs -Dbitbrains.allowSubset=true)
 *   -Dbitbrains.stepsPerDay=288
 *   -Dbitbrains.days=1                                  run only the first N days (one-day test); default all
 *   -Dbitbrains.maxMinutes=0                            stop after this many minutes (0 = no limit)
 *   -Dbitbrains.fresh=true                              archive old batch results and start over
 *   -Dbitbrains.allowSkippedFiles=true                  continue even if some CSV files could not be used
 *
 * To stop: Ctrl+C / close the window (only the day in progress is lost), or create an empty file named STOP
 * to stop cleanly after the current day.
 */
public final class TraceBatchRunner {

    private static final Path RESULTS = Path.of("results");
    private static final Path STOP_FILE = Path.of("STOP");

    public static void main(String[] args) throws IOException {
        Path dataDir = Path.of(System.getProperty("bitbrains.dir", "data/bitbrains/fastStorage/2013-8"));
        int vmsOption = Integer.getInteger("bitbrains.vms", 0);
        int stepsPerDay = Integer.getInteger("bitbrains.stepsPerDay", 288);
        int daysLimit = Integer.getInteger("bitbrains.days", Integer.MAX_VALUE);
        double maxMinutes = Double.parseDouble(System.getProperty("bitbrains.maxMinutes", "0"));
        boolean fresh = Boolean.getBoolean("bitbrains.fresh");
        boolean allowSubset = Boolean.getBoolean("bitbrains.allowSubset");
        boolean allowSkipped = Boolean.getBoolean("bitbrains.allowSkippedFiles");

        if (!BitbrainsTraceLoader.isAvailable(dataDir)) {
            System.out.println("ERROR: no Bitbrains CSV files found in " + dataDir.toAbsolutePath());
            return;
        }
        if (vmsOption != 0 && !allowSubset) {
            System.out.println("ERROR: -Dbitbrains.vms=" + vmsOption + " would use only part of the dataset. "
                    + "The final experiment must use all files (-Dbitbrains.vms=0).");
            return;
        }
        Files.createDirectories(RESULTS);
        Files.deleteIfExists(STOP_FILE); // an old STOP file must not block a new start
        long batchStart = System.nanoTime();

        // ---- 1. dataset: load everything and verify the file count -----------------------------------
        int csvFiles = countCsvFiles(dataDir);
        BitbrainsTraceLoader.Trace full = loadTrace(dataDir, vmsOption, csvFiles);
        if (vmsOption == 0 && full.vmFiles() != csvFiles && !allowSkipped) {
            System.out.printf("ERROR: the folder has %d CSV files but only %d could be used (see the 'skipping' "
                    + "lines above). Fix the data, or start with -Dbitbrains.allowSkippedFiles=true.%n", csvFiles, full.vmFiles());
            return;
        }

        // ---- 2. days and global normalisation --------------------------------------------------------
        int days = full.steps() / stepsPerDay;
        if (days < 1) {
            System.out.println("ERROR: the trace has only " + full.steps() + " samples, less than one day of " + stepsPerDay);
            return;
        }
        int usedSteps = days * stepsPerDay;
        int trailing = full.steps() - usedSteps;
        BitbrainsTraceLoader.Trace used = full.window(0, usedSteps);
        double p95 = used.percentile(0.95);
        if (p95 <= 0) {
            System.out.println("ERROR: the P95 of the trace is 0, the load cannot be normalised.");
            return;
        }
        double targetShare = TraceBenchmarkRunner.targetP95Load();
        double scale = targetShare * 100.0 / p95;
        double[] offeredAll = new double[usedSteps];
        for (int i = 0; i < usedSteps; i++) {
            offeredAll[i] = used.cpuPercent()[i] / 100.0 * scale;
        }
        int daysToRun = (int) Math.min((long) days, (long) daysLimit);

        String banner = String.format(Locale.US,
                "Dataset directory:        %s%n"
                        + "VM files:                 %d (folder has %d CSV files)%n"
                        + "Total trace steps:        %d (%d simulated, %d trailing endpoint sample(s) not simulated)%n"
                        + "Full-trace mean CPU:      %.4f %% (simulated steps; all samples: %.4f %%)%n"
                        + "Full-trace P95:           %.4f %% (simulated steps; all samples: %.4f %%)%n"
                        + "Global load scale:        x%.4f%n"
                        + "Target P95:               %.0f %% of the capacity of the baseline VM pool%n"
                        + "Steps per day:            %d%n"
                        + "Number of days:           %d (this run: %d)%n",
                dataDir, full.vmFiles(), csvFiles, full.steps(), usedSteps, trailing,
                used.mean(), full.mean(), p95, full.percentile(0.95), scale, targetShare * 100.0,
                stepsPerDay, days, daysToRun);
        System.out.println("\n" + banner);
        if (days != 30) {
            System.out.println("NOTE: the plan expects 30 days; this trace gives " + days + ".");
        }

        // ---- 3. settings signature: results of different data/settings must not be mixed ----------------
        String signature = "dir=" + dataDir + ";csvFiles=" + csvFiles + ";vms=" + vmsOption + ";stepsPerDay=" + stepsPerDay
                + ";samples=" + full.steps() + ";globalScale=" + String.format(Locale.US, "%.10f", scale)
                + ";" + TraceBenchmarkRunner.settingsSignature();
        BatchCheckpoint cp = new BatchCheckpoint(RESULTS);
        if (fresh && cp.anyResultFiles()) {
            System.out.println("Archived the old batch results to " + cp.archiveAll() + " (fresh start requested).");
        }
        String oldSignature = cp.readSignature();
        if (cp.anyResultFiles() && (oldSignature == null || !oldSignature.equals(signature))) {
            System.out.println("ERROR: results/ already holds day files made with different data or settings:\n  old: "
                    + oldSignature + "\n  new: " + signature
                    + "\nThey must not be mixed. Run start_fresh.bat (archives the old results) or restore the old settings.");
            return;
        }
        cp.writeSignature(signature);
        writeConfig(RESULTS.resolve("bitbrains_batch_config.md"), banner, signature, days, daysToRun);
        writeTraceUsed(RESULTS.resolve("bitbrains_batch_trace_used.csv"), used, offeredAll, stepsPerDay);

        // ---- 4. resume point and SARSA state --------------------------------------------------------------
        TraceBenchmarkRunner runner = new TraceBenchmarkRunner();
        int firstMissing = cp.firstMissingDay(days);
        if (cp.hasDayFilesAfter(firstMissing, days)) {
            System.out.println("ERROR: day " + firstMissing + " is missing but later day files exist. "
                    + "The days must be consecutive because SARSA learning carries over. Restore the missing file or start fresh.");
            return;
        }
        int lastValid = cp.lastValidDay(firstMissing - 1);
        if (lastValid > 0) {
            Path q = cp.qtableDayFile(lastValid);
            if (!Files.exists(q)) {
                System.out.println("ERROR: day " + lastValid + " is valid but its Q-table file " + q
                        + " is missing, so learning cannot continue consistently. Restore it or start fresh.");
                return;
            }
            QTableStore.load(runner.sarsaAgent(), q);
            System.out.println("Resuming: days 1.." + (firstMissing - 1) + " are done; Q-table of day " + lastValid + " loaded.");
        } else if (firstMissing > 1) {
            System.out.println("Resuming after " + (firstMissing - 1) + " finished day(s), none valid; SARSA starts from the initial Q-table.");
        } else {
            System.out.println("Starting at day 1 with the initial Q-table.");
        }
        System.out.println("To stop: Ctrl+C or close the window, or create an empty file named STOP in the project folder.\n");

        // ---- 5. day loop ------------------------------------------------------------------------------------
        int ran = 0;
        int invalidNow = 0;
        double sumDaySeconds = 0.0;
        String stopReason = null;
        for (int day = firstMissing; day <= daysToRun; day++) {
            if (Files.exists(STOP_FILE)) {
                stopReason = "STOP file found";
                break;
            }
            if (maxMinutes > 0 && (System.nanoTime() - batchStart) / 1e9 / 60.0 >= maxMinutes) {
                stopReason = "time limit of " + maxMinutes + " min reached";
                break;
            }

            long dayStart = System.nanoTime();
            int from = (day - 1) * stepsPerDay;
            BitbrainsTraceLoader.Trace dayTrace = used.window(from, stepsPerDay);
            double[] offered = Arrays.copyOfRange(offeredAll, from, from + stepsPerDay);
            double[] qBefore = QTableStore.snapshot(runner.sarsaAgent());

            System.out.printf("%n[batch] ===== day %d of %d =====%n", day, days);
            List<RunRecord> records = new ArrayList<>();
            String reason = null;
            double expectedCloudlets = Arrays.stream(offered).sum() * TraceBenchmarkRunner.capacityPerStep();
            if (expectedCloudlets < 1.0) {
                reason = "this day has no workload";
            } else {
                try {
                    runner.runDay(dayTrace, offered, records);
                } catch (RuntimeException e) {
                    reason = "simulation error: " + e;
                }
            }
            if (reason == null) {
                reason = BatchCheckpoint.validate(records, TraceBenchmarkRunner.runsPerDay());
            }

            Files.writeString(cp.timelineFile(day), runner.timelineCsv());
            if (reason == null) {
                QTableStore.save(runner.sarsaAgent(), cp.qtableDayFile(day), day);
                QTableStore.save(runner.sarsaAgent(), cp.qtableLatestFile(), day);
                cp.writeDay(day, records, true, null);
            } else {
                QTableStore.restore(runner.sarsaAgent(), qBefore); // an invalid day must not teach SARSA anything
                cp.writeDay(day, records, false, reason);
                invalidNow++;
                System.out.println("[batch] DAY " + day + " IS INVALID and is excluded: " + reason);
            }
            ran++;
            double daySeconds = (System.nanoTime() - dayStart) / 1e9;
            sumDaySeconds += daySeconds;

            int valid = BatchAggregator.aggregate(RESULTS, days, TraceBenchmarkRunner.runsPerDay(),
                    RESULTS.resolve("bitbrains_batch_summary.csv"), RESULTS.resolve("bitbrains_batch_summary.md"));
            System.out.printf(Locale.US,
                    "[batch] day %d %s in %.0f s. Valid days so far: %d. About %.0f min left for this run.%n",
                    day, reason == null ? "valid" : "INVALID", daySeconds, valid,
                    (daysToRun - day) * (sumDaySeconds / ran) / 60.0);
        }

        int valid = BatchAggregator.aggregate(RESULTS, days, TraceBenchmarkRunner.runsPerDay(),
                RESULTS.resolve("bitbrains_batch_summary.csv"), RESULTS.resolve("bitbrains_batch_summary.md"));
        System.out.printf(Locale.US,
                "%n============================================================%n"
                        + " BATCH %s after %.1f min%n"
                        + " Days run now: %d (invalid now: %d). Valid days in total: %d of %d planned.%n"
                        + " Summary : results/bitbrains_batch_summary.md%n"
                        + " Q-table : results/bitbrains_sarsa_qtable.csv%n"
                        + "============================================================%n",
                stopReason == null ? "FINISHED" : "STOPPED (" + stopReason + ")",
                (System.nanoTime() - batchStart) / 1e9 / 60.0, ran, invalidNow, valid, days);
        Files.deleteIfExists(STOP_FILE);
    }

    private static int countCsvFiles(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir, 4)) {
            return (int) files.filter(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv")).count();
        }
    }

    /** Parsing 1250 files takes minutes, so the aggregated trace is cached (keyed by the number of files). */
    private static BitbrainsTraceLoader.Trace loadTrace(Path dir, int vms, int csvFiles) throws IOException {
        Path cache = RESULTS.resolve("bitbrains_trace_cache_files" + csvFiles + "_vms" + vms + ".csv");
        long start = System.nanoTime();
        if (Files.exists(cache)) {
            try {
                BitbrainsTraceLoader.Trace trace = BitbrainsTraceLoader.loadCache(cache);
                System.out.printf(Locale.US, "Loaded the cached trace (%d VM files, %d steps) in %.0f s. "
                                + "Delete %s to read the CSV files again.%n",
                        trace.vmFiles(), trace.steps(), (System.nanoTime() - start) / 1e9, cache);
                return trace;
            } catch (IOException | RuntimeException e) {
                System.out.println("Cache unreadable (" + e.getMessage() + "), reading the CSV files again.");
            }
        }
        System.out.println("Reading " + csvFiles + " CSV files (only the first time, a few minutes) ...");
        BitbrainsTraceLoader.Trace trace = BitbrainsTraceLoader.load(dir, vms);
        BitbrainsTraceLoader.saveCache(trace, cache);
        System.out.printf(Locale.US, "Read %d VM files in %.0f s (cached for the next start).%n",
                trace.vmFiles(), (System.nanoTime() - start) / 1e9);
        return trace;
    }

    private static void writeConfig(Path file, String banner, String signature, int days, int daysToRun) throws IOException {
        StringBuilder sb = new StringBuilder("# Bitbrains batch configuration\n\n");
        sb.append("Written at the start of every run of TraceBatchRunner. Real-workload evaluation, run after the ")
                .append("paper-aligned baseline; it does not reproduce the paper's synthetic benchmark.\n\n```\n")
                .append(banner).append("```\n\n");
        sb.append("- One global load scale for all days: the P95 of the simulated trace is mapped to the stated share of ")
                .append("the capacity of the baseline VM pool (not recalculated per day).\n");
        sb.append("- Every day: STATIC, THRESHOLD_ONLY and ").append(TraceBenchmarkRunner.sarsaRepetitions())
                .append(" SARSA repetitions receive exactly the same offered-load sequence.\n");
        sb.append("- Days run in order; the SARSA Q-table carries over from day to day and is saved after every valid day.\n");
        sb.append("- A day counts only if every run finished all of its cloudlets.\n");
        sb.append("- Raw and scaled load of every step: results/bitbrains_batch_trace_used.csv\n\n");
        sb.append("Settings signature (results are only resumed when this is identical):\n\n```\n")
                .append(signature).append("\n```\n");
        BatchCheckpoint.writeAtomically(file, sb.toString());
    }

    private static void writeTraceUsed(Path file, BitbrainsTraceLoader.Trace used, double[] offeredAll, int stepsPerDay)
            throws IOException {
        StringBuilder sb = new StringBuilder("step,day,step_in_day,raw_cpu_percent,active_vms,offered_load_percent\n");
        for (int i = 0; i < offeredAll.length; i++) {
            sb.append(String.format(Locale.US, "%d,%d,%d,%.6f,%d,%.6f%n", i, i / stepsPerDay + 1, i % stepsPerDay,
                    used.cpuPercent()[i], used.activeVms()[i], offeredAll[i] * 100.0));
        }
        BatchCheckpoint.writeAtomically(file, sb.toString());
    }
}

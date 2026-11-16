package org.example.simulation;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * File layout and bookkeeping of the multi-day Bitbrains batch (no CloudSim classes).
 *
 *   results/bitbrains_dayNN.csv               one file per finished day, valid or invalid
 *                                             (first line: "# status=VALID" or "# status=INVALID reason=...")
 *   results/bitbrains_dayNN_timeline.csv      controller timeline of that day
 *   results/bitbrains_sarsa_qtable_dayNN.csv  Q-table after valid day NN
 *   results/bitbrains_sarsa_qtable.csv        copy of the latest one
 *   results/bitbrains_batch_signature.txt     data + settings the results belong to
 *
 * A day file is the "commit" of a day: it is written last, so a day without its file is
 * simply run again after a restart.
 */
public final class BatchCheckpoint {

    public static final String DAY_HEADER =
            "day,mode,repetition,cloudlets,completed,lost_on_destroy,makespan,avg_response,avg_waiting,"
                    + "p95_response,throughput_per_s,avg_vms,vm_seconds,scale_out,scale_in,flaps";

    private final Path dir;

    public BatchCheckpoint(Path resultsDir) {
        this.dir = resultsDir;
    }

    public Path dayFile(int day) {
        return dir.resolve(String.format("bitbrains_day%02d.csv", day));
    }

    public Path timelineFile(int day) {
        return dir.resolve(String.format("bitbrains_day%02d_timeline.csv", day));
    }

    public Path qtableDayFile(int day) {
        return dir.resolve(String.format("bitbrains_sarsa_qtable_day%02d.csv", day));
    }

    public Path qtableLatestFile() {
        return dir.resolve("bitbrains_sarsa_qtable.csv");
    }

    public Path signatureFile() {
        return dir.resolve("bitbrains_batch_signature.txt");
    }

    public boolean dayDone(int day) {
        return Files.exists(dayFile(day));
    }

    public boolean dayMarkedValid(int day) {
        try {
            return Files.exists(dayFile(day))
                    && Files.readAllLines(dayFile(day)).get(0).startsWith("# status=VALID");
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** First day in 1..days without a day file, or days+1 if all are done. */
    public int firstMissingDay(int days) {
        for (int d = 1; d <= days; d++) {
            if (!dayDone(d)) {
                return d;
            }
        }
        return days + 1;
    }

    /** True if a day file exists after the given day (an unexpected gap). */
    public boolean hasDayFilesAfter(int day, int days) {
        for (int d = day + 1; d <= days; d++) {
            if (dayDone(d)) {
                return true;
            }
        }
        return false;
    }

    /** Highest valid day not above upTo, or 0. */
    public int lastValidDay(int upTo) {
        for (int d = upTo; d >= 1; d--) {
            if (dayMarkedValid(d)) {
                return d;
            }
        }
        return 0;
    }

    public boolean anyResultFiles() throws IOException {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.anyMatch(p -> p.getFileName().toString().matches("bitbrains_day\\d+\\.csv"));
        }
    }

    public String readSignature() throws IOException {
        return Files.exists(signatureFile()) ? Files.readString(signatureFile()).trim() : null;
    }

    public void writeSignature(String signature) throws IOException {
        writeAtomically(signatureFile(), signature + "\n");
    }

    /** Moves all batch result files into results/bitbrains_archive_TIMESTAMP/. */
    public Path archiveAll() throws IOException {
        Path archive = dir.resolve("bitbrains_archive_" + System.currentTimeMillis());
        Files.createDirectories(archive);
        Set<Path> toMove = new HashSet<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> {
                String n = p.getFileName().toString();
                return n.matches("bitbrains_day\\d+(_timeline)?\\.csv")
                        || n.startsWith("bitbrains_sarsa_qtable")
                        || n.startsWith("bitbrains_batch_");
            }).forEach(toMove::add);
        }
        for (Path p : toMove) {
            Files.move(p, archive.resolve(p.getFileName()));
        }
        return archive;
    }

    /** @return null if the day is valid, otherwise the reason it is not */
    public static String validate(List<RunRecord> records, int expectedRows) {
        if (records.size() != expectedRows) {
            return "expected " + expectedRows + " runs but only " + records.size() + " finished";
        }
        Set<String> modes = new HashSet<>();
        int cloudlets = records.get(0).cloudlets();
        for (RunRecord r : records) {
            modes.add(r.mode());
            if (r.cloudlets() != cloudlets) {
                return "the modes did not receive the same workload (" + cloudlets + " vs " + r.cloudlets() + " cloudlets)";
            }
            if (!r.isComplete()) {
                return String.format(Locale.US, "%s rep %d finished %d of %d cloudlets (%d lost when a VM was removed)",
                        r.mode(), r.repetition(), r.completed(), r.cloudlets(), r.lostOnDestroy());
            }
        }
        if (modes.size() != 3) {
            return "not all three modes ran: " + modes;
        }
        return null;
    }

    public void writeDay(int day, List<RunRecord> records, boolean valid, String reason) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (valid) {
            sb.append("# status=VALID\n");
        } else {
            sb.append("# status=INVALID reason=")
                    .append(String.valueOf(reason).replace('\n', ' ').replace('\r', ' ')).append('\n');
        }
        sb.append(DAY_HEADER).append('\n');
        for (RunRecord r : records) {
            sb.append(String.format(Locale.US, "%d,%s,%d,%d,%d,%d,%.6f,%.6f,%.6f,%.6f,%.8f,%.6f,%.4f,%d,%d,%d%n",
                    day, r.mode(), r.repetition(), r.cloudlets(), r.completed(), r.lostOnDestroy(),
                    r.makespan(), r.avgResponse(), r.avgWaiting(), r.p95Response(), r.throughput(),
                    r.avgVms(), r.vmSeconds(), r.scaleOut(), r.scaleIn(), r.flaps()));
        }
        writeAtomically(dayFile(day), sb.toString());
    }

    public static void writeAtomically(Path file, String content) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

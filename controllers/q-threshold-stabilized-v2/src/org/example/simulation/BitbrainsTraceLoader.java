package org.example.simulation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Loads the Bitbrains GWA-T-12 trace (fastStorage / Rnd) and aggregates it into
 * one CPU-utilization time series.
 *
 * Expected input: a directory with one CSV per VM (e.g. 1.csv, 2.csv, ...), or a
 * single CSV file. Each file has a header such as
 *   Timestamp [ms]; CPU cores; CPU capacity provisioned [MHZ]; CPU usage [MHZ]; CPU usage [%]; ...
 * Columns are found by name, the delimiter (';' or ',') is auto-detected, and the
 * timestamp unit is not assumed: the sampling step is the median gap between
 * consecutive samples, and samples are aligned across VMs by step index.
 *
 * Output per step: mean CPU usage [%] over all VMs that reported in that step.
 */
public final class BitbrainsTraceLoader {

    /** Aggregated trace. cpuPercent[i] is the mean CPU% across reporting VMs at step i. */
    public record Trace(
            double[] cpuPercent,
            int[] activeVms,
            long stepRaw,
            int vmFiles) {

        public int steps() {
            return cpuPercent.length;
        }

        /** Returns a copy restricted to [start, start+length). */
        public Trace window(int start, int length) {
            int from = Math.max(0, Math.min(start, cpuPercent.length));
            int to = Math.max(from, Math.min(from + length, cpuPercent.length));
            return new Trace(
                    Arrays.copyOfRange(cpuPercent, from, to),
                    Arrays.copyOfRange(activeVms, from, to),
                    stepRaw,
                    vmFiles);
        }

        public double mean() {
            return Arrays.stream(cpuPercent).average().orElse(0.0);
        }

        public double percentile(double p) {
            if (cpuPercent.length == 0) {
                return 0.0;
            }
            double[] sorted = cpuPercent.clone();
            Arrays.sort(sorted);
            int idx = (int) Math.min(sorted.length - 1, Math.max(0, Math.ceil(p * sorted.length) - 1));
            return sorted[idx];
        }
    }

    private BitbrainsTraceLoader() {}

    /** True if the path is a CSV file or a directory containing at least one CSV file. */
    public static boolean isAvailable(Path path) {
        if (path == null || !Files.exists(path)) {
            return false;
        }
        if (Files.isRegularFile(path)) {
            return true;
        }
        try (Stream<Path> files = Files.walk(path, 4)) {
            return files.anyMatch(BitbrainsTraceLoader::isCsv);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Loads and aggregates the trace.
     *
     * @param path        directory of per-VM CSV files, or a single CSV file
     * @param maxVmFiles  use at most this many VM files (sorted by name); <= 0 means all
     */
    public static Trace load(Path path, int maxVmFiles) {
        List<Path> files = listFiles(path, maxVmFiles);
        if (files.isEmpty()) {
            throw new IllegalArgumentException("No Bitbrains CSV files found in " + path);
        }

        List<long[]> rawTimestamps = new ArrayList<>();
        List<double[]> rawCpu = new ArrayList<>();
        for (Path file : files) {
            parseFile(file, rawTimestamps, rawCpu);
        }
        if (rawTimestamps.isEmpty()) {
            throw new IllegalArgumentException("No usable CPU rows found in " + path);
        }

        long globalMin = Long.MAX_VALUE;
        for (long[] ts : rawTimestamps) {
            globalMin = Math.min(globalMin, ts[0]);
        }
        long step = medianStep(rawTimestamps);

        // step index -> {sum, count}
        TreeMap<Long, double[]> buckets = new TreeMap<>();
        for (int v = 0; v < rawTimestamps.size(); v++) {
            long[] ts = rawTimestamps.get(v);
            double[] cpu = rawCpu.get(v);
            for (int i = 0; i < ts.length; i++) {
                long index = Math.round((double) (ts[i] - globalMin) / step);
                double[] acc = buckets.computeIfAbsent(index, k -> new double[2]);
                acc[0] += cpu[i];
                acc[1] += 1.0;
            }
        }

        // Dense series from the first to the last bucket; empty buckets repeat the
        // previous value so the series has no holes.
        long first = buckets.firstKey();
        long last = buckets.lastKey();
        int n = (int) (last - first + 1);
        double[] series = new double[n];
        int[] active = new int[n];
        double previous = 0.0;
        for (int i = 0; i < n; i++) {
            double[] acc = buckets.get(first + i);
            if (acc != null && acc[1] > 0) {
                previous = acc[0] / acc[1];
                active[i] = (int) acc[1];
            }
            series[i] = previous;
        }
        return new Trace(series, active, step, rawTimestamps.size());
    }

    /** Saves the aggregated trace so the 1250 files do not have to be parsed again after a restart. */
    public static void saveCache(Trace trace, Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# stepRaw=").append(trace.stepRaw()).append(" vmFiles=").append(trace.vmFiles()).append('\n');
        sb.append("step,cpu_percent,active_vms\n");
        for (int i = 0; i < trace.steps(); i++) {
            sb.append(i).append(',').append(trace.cpuPercent()[i]).append(',').append(trace.activeVms()[i]).append('\n');
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    /** Reads a file written by {@link #saveCache}. */
    public static Trace loadCache(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.size() < 3 || !lines.get(0).startsWith("# stepRaw=")) {
            throw new IOException("Not a valid trace cache: " + file);
        }
        long stepRaw = 1;
        int vmFiles = 0;
        for (String part : lines.get(0).substring(2).split(" ")) {
            if (part.startsWith("stepRaw=")) {
                stepRaw = Long.parseLong(part.substring(8));
            } else if (part.startsWith("vmFiles=")) {
                vmFiles = Integer.parseInt(part.substring(8));
            }
        }
        int n = lines.size() - 2;
        double[] cpu = new double[n];
        int[] active = new int[n];
        for (int i = 0; i < n; i++) {
            String[] cells = lines.get(i + 2).split(",");
            cpu[i] = Double.parseDouble(cells[1]);
            active[i] = Integer.parseInt(cells[2]);
        }
        return new Trace(cpu, active, stepRaw, vmFiles);
    }

    private static List<Path> listFiles(Path path, int maxVmFiles) {
        List<Path> files = new ArrayList<>();
        try {
            if (Files.isRegularFile(path)) {
                files.add(path);
            } else {
                // The downloaded zips keep the CSVs in sub-folders (fastStorage/2013-8/,
                // Rnd/2013-7/ ...). Search below the folder, but use ONE sub-folder only:
                // different months have different timestamps and must not be mixed.
                List<Path> found;
                try (Stream<Path> stream = Files.walk(path, 4)) {
                    found = stream.filter(BitbrainsTraceLoader::isCsv).toList();
                }
                Path chosenDir = found.stream()
                        .map(Path::getParent)
                        .min((a, b) -> a.toString().compareTo(b.toString()))
                        .orElse(null);
                long otherDirs = found.stream().map(Path::getParent).distinct().count() - 1;
                if (otherDirs > 0) {
                    System.out.println("[bitbrains] CSV files found in " + (otherDirs + 1)
                            + " folders; using only " + chosenDir
                            + " (point -Dbitbrains.dir at another folder to use a different month).");
                }
                found.stream()
                        .filter(p -> p.getParent().equals(chosenDir))
                        .sorted((a, b) -> naturalCompare(a.getFileName().toString(), b.getFileName().toString()))
                        .forEach(files::add);
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to read " + path, e);
        }
        if (maxVmFiles > 0 && files.size() > maxVmFiles) {
            return new ArrayList<>(files.subList(0, maxVmFiles));
        }
        return files;
    }

    private static boolean isCsv(Path p) {
        return Files.isRegularFile(p) && p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv");
    }

    /** Sorts "2.csv" before "10.csv". */
    private static int naturalCompare(String a, String b) {
        String na = a.replaceAll("\\D", "");
        String nb = b.replaceAll("\\D", "");
        if (!na.isEmpty() && !nb.isEmpty() && na.length() < 18 && nb.length() < 18) {
            int c = Long.compare(Long.parseLong(na), Long.parseLong(nb));
            if (c != 0) {
                return c;
            }
        }
        return a.compareTo(b);
    }

    private static void parseFile(Path file, List<long[]> timestampsOut, List<double[]> cpuOut) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Unable to read " + file, e);
        }
        if (lines.size() < 2) {
            return;
        }

        String header = lines.get(0);
        String delimiter = header.contains(";") ? ";" : ",";
        String[] columns = header.split(delimiter, -1);

        int tsCol = -1;
        int cpuPctCol = -1;
        int cpuMhzCol = -1;
        int capacityCol = -1;
        for (int i = 0; i < columns.length; i++) {
            String name = columns[i].trim().toLowerCase(Locale.ROOT);
            if (name.startsWith("timestamp")) {
                tsCol = i;
            } else if (name.startsWith("cpu usage") && name.contains("[%]")) {
                cpuPctCol = i;
            } else if (name.startsWith("cpu usage") && name.contains("mhz")) {
                cpuMhzCol = i;
            } else if (name.startsWith("cpu capacity provisioned")) {
                capacityCol = i;
            }
        }
        if (tsCol < 0 || (cpuPctCol < 0 && (cpuMhzCol < 0 || capacityCol < 0))) {
            System.out.println("[bitbrains] skipping " + file.getFileName()
                    + ": expected 'Timestamp' and 'CPU usage [%]' columns");
            return;
        }

        long[] ts = new long[lines.size() - 1];
        double[] cpu = new double[lines.size() - 1];
        int count = 0;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] cells = line.split(delimiter, -1);
            try {
                long t = (long) Double.parseDouble(cells[tsCol].trim());
                double pct;
                if (cpuPctCol >= 0) {
                    pct = Double.parseDouble(cells[cpuPctCol].trim());
                } else {
                    double capacity = Double.parseDouble(cells[capacityCol].trim());
                    pct = capacity > 0 ? 100.0 * Double.parseDouble(cells[cpuMhzCol].trim()) / capacity : 0.0;
                }
                if (!Double.isFinite(pct)) {
                    continue;
                }
                ts[count] = t;
                cpu[count] = Math.max(0.0, Math.min(100.0, pct));
                count++;
            } catch (RuntimeException e) {
                // malformed row: skip it
            }
        }
        if (count > 0) {
            timestampsOut.add(Arrays.copyOf(ts, count));
            cpuOut.add(Arrays.copyOf(cpu, count));
        }
    }

    /** Median gap between consecutive samples over all VMs (unit-agnostic). */
    private static long medianStep(List<long[]> allTimestamps) {
        List<Long> gaps = new ArrayList<>();
        for (long[] ts : allTimestamps) {
            for (int i = 1; i < ts.length && gaps.size() < 200_000; i++) {
                long gap = ts[i] - ts[i - 1];
                if (gap > 0) {
                    gaps.add(gap);
                }
            }
        }
        if (gaps.isEmpty()) {
            return 1;
        }
        gaps.sort(Long::compare);
        return Math.max(1, gaps.get(gaps.size() / 2));
    }
}

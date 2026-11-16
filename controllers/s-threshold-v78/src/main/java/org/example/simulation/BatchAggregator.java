package org.example.simulation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;

/**
 * Builds the final tables from the saved per-day files results/bitbrains_dayNN.csv.
 *
 *  - Only valid days count. A day is valid if its file says VALID AND the rows pass the
 *    check again (right number of runs, all cloudlets finished, same workload for all modes).
 *  - For each day and mode the value is the mean of that mode's repetitions (S_THRESHOLD_SARSA has
 *    several); the mean and standard deviation are then taken over days, so the repetitions of one
 *    day are not counted as independent days.
 *  - Paired comparison: for every valid day, S_THRESHOLD_SARSA minus THRESHOLD_ONLY.
 *
 * No CloudSim classes are used, so it can be tested on its own.
 */
public final class BatchAggregator {

    public static final String SARSA = "S_THRESHOLD_SARSA";
    public static final String THRESHOLD = "THRESHOLD_ONLY";
    public static final String STATIC = "STATIC";

    private static final Map<String, ToDoubleFunction<RunRecord>> METRICS = new LinkedHashMap<>();
    static {
        METRICS.put("makespan", RunRecord::makespan);
        METRICS.put("avg_response", RunRecord::avgResponse);
        METRICS.put("avg_waiting", RunRecord::avgWaiting);
        METRICS.put("p95_response", RunRecord::p95Response);
        METRICS.put("throughput_per_s", RunRecord::throughput);
        METRICS.put("avg_vms", RunRecord::avgVms);
        METRICS.put("vm_seconds", RunRecord::vmSeconds);
        METRICS.put("scale_out", r -> r.scaleOut());
        METRICS.put("scale_in", r -> r.scaleIn());
        METRICS.put("flaps", r -> r.flaps());
    }

    private static final String[] PAIRED = {
            "avg_response", "p95_response", "avg_waiting", "makespan", "vm_seconds", "avg_vms", "flaps"};

    private record Day(int day, boolean valid, String reason, List<RunRecord> rows) {}

    private BatchAggregator() {}

    /** @return number of valid days used */
    public static int aggregate(Path resultsDir, int plannedDays, int expectedRowsPerDay,
                                Path outCsv, Path outMd) throws IOException {
        List<Day> days = readDays(resultsDir, expectedRowsPerDay);
        List<Day> valid = days.stream().filter(Day::valid).toList();
        List<Day> invalid = days.stream().filter(d -> !d.valid()).toList();

        StringBuilder md = new StringBuilder("# Bitbrains 30-day real-workload evaluation\n\n");
        md.append("This is the real-workload evaluation after the paper-aligned baseline. ")
                .append("It does not reproduce the paper's synthetic benchmark.\n\n");
        md.append(String.format(Locale.US, "- Planned days: %d%n- Finished days: %d%n- **Valid days used: %d**%n- Invalid days: %d",
                plannedDays, days.size(), valid.size(), invalid.size()));
        if (!invalid.isEmpty()) {
            md.append(" (");
            for (int i = 0; i < invalid.size(); i++) {
                md.append(i > 0 ? "; " : "").append("day ").append(invalid.get(i).day())
                        .append(": ").append(invalid.get(i).reason());
            }
            md.append(")");
        }
        md.append("\n\n");

        StringBuilder csv = new StringBuilder("section,name,metric,valid_days,mean,std,days_lower,days_equal,days_higher\n");

        if (valid.isEmpty()) {
            md.append("No valid day yet.\n");
            BatchCheckpoint.writeAtomically(outCsv, csv.toString());
            BatchCheckpoint.writeAtomically(outMd, md.toString());
            return 0;
        }

        md.append("All values are mean +/- standard deviation over the valid days. Per day, a mode's value is the mean of its ")
                .append("repetitions. Times are CloudSim seconds. Response and waiting time are measured from each ")
                .append("cloudlet's arrival; throughput is completed cloudlets divided by makespan.\n\n");
        md.append("| Mode | Avg response | P95 response | Avg waiting | Avg VMs | VM-seconds | Makespan | Throughput/s | Scale-out | Scale-in | Flaps |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (String mode : new String[] {STATIC, THRESHOLD, SARSA}) {
            md.append("| ").append(mode);
            for (String metric : new String[] {"avg_response", "p95_response", "avg_waiting", "avg_vms", "vm_seconds",
                    "makespan", "throughput_per_s", "scale_out", "scale_in", "flaps"}) {
                double[] values = perDay(valid, mode, metric);
                md.append(String.format(Locale.US, " | %s", format(metric, mean(values), std(values))));
                csv.append(String.format(Locale.US, "mode,%s,%s,%d,%.8f,%.8f,,,%n",
                        mode, metric, values.length, mean(values), std(values)));
            }
            md.append(" |\n");
        }

        md.append("\n## Paired comparison per valid day: ").append(SARSA).append(" minus ").append(THRESHOLD).append("\n\n");
        md.append("Negative = SARSA is lower (for times, VM-seconds and flaps, lower is better). The same days are compared, ")
                .append("so day-to-day differences in load cancel out.\n\n");
        md.append("| Metric | Mean difference +/- SD | Days SARSA lower | Days equal | Days SARSA higher |\n|---|---:|---:|---:|---:|\n");
        for (String metric : PAIRED) {
            double[] a = perDay(valid, SARSA, metric);
            double[] b = perDay(valid, THRESHOLD, metric);
            double[] diff = new double[a.length];
            int lower = 0;
            int equal = 0;
            int higher = 0;
            for (int i = 0; i < a.length; i++) {
                diff[i] = a[i] - b[i];
                if (Math.abs(diff[i]) < 1e-9) {
                    equal++;
                } else if (diff[i] < 0) {
                    lower++;
                } else {
                    higher++;
                }
            }
            md.append(String.format(Locale.US, "| %s | %s | %d | %d | %d |%n",
                    metric, format(metric, mean(diff), std(diff)), lower, equal, higher));
            csv.append(String.format(Locale.US, "paired_sarsa_minus_threshold,diff,%s,%d,%.8f,%.8f,%d,%d,%d%n",
                    metric, diff.length, mean(diff), std(diff), lower, equal, higher));
        }

        BatchCheckpoint.writeAtomically(outCsv, csv.toString());
        BatchCheckpoint.writeAtomically(outMd, md.toString());
        return valid.size();
    }

    private static String format(String metric, double mean, double std) {
        String f = metric.equals("throughput_per_s") ? "%.5f +/- %.5f"
                : metric.startsWith("vm_seconds") || metric.equals("makespan") ? "%.1f +/- %.1f" : "%.3f +/- %.3f";
        return String.format(Locale.US, f, mean, std);
    }

    private static List<Day> readDays(Path resultsDir, int expectedRows) throws IOException {
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(resultsDir)) {
            try (Stream<Path> stream = Files.list(resultsDir)) {
                stream.filter(p -> p.getFileName().toString().matches("bitbrains_day\\d{2}\\.csv"))
                        .sorted().forEach(files::add);
            }
        }
        List<Day> days = new ArrayList<>();
        for (Path file : files) {
            int day = Integer.parseInt(file.getFileName().toString().substring(13, 15));
            List<String> lines = Files.readAllLines(file);
            boolean statusValid = !lines.isEmpty() && lines.get(0).startsWith("# status=VALID");
            String reason = statusValid ? null
                    : (lines.isEmpty() ? "empty file" : lines.get(0).replaceFirst("^# status=INVALID reason=", ""));
            List<RunRecord> rows = new ArrayList<>();
            for (int i = 2; i < lines.size(); i++) {
                if (!lines.get(i).isBlank()) {
                    rows.add(parseRow(lines.get(i)));
                }
            }
            if (statusValid) {
                String check = BatchCheckpoint.validate(rows, expectedRows);
                if (check != null) {
                    statusValid = false;
                    reason = "failed re-check: " + check;
                }
            }
            days.add(new Day(day, statusValid, reason, rows));
        }
        return days;
    }

    private static RunRecord parseRow(String line) {
        String[] c = line.split(",");
        return new RunRecord(c[1], Integer.parseInt(c[2]), Integer.parseInt(c[3]), Integer.parseInt(c[4]),
                Integer.parseInt(c[5]), Double.parseDouble(c[6]), Double.parseDouble(c[7]), Double.parseDouble(c[8]),
                Double.parseDouble(c[9]), Double.parseDouble(c[10]), Double.parseDouble(c[11]),
                Double.parseDouble(c[12]), Integer.parseInt(c[13]), Integer.parseInt(c[14]), Integer.parseInt(c[15]));
    }

    private static double[] perDay(List<Day> valid, String mode, String metric) {
        ToDoubleFunction<RunRecord> f = METRICS.get(metric);
        return valid.stream()
                .mapToDouble(d -> d.rows().stream().filter(r -> r.mode().equals(mode)).mapToDouble(f).average().orElse(0.0))
                .toArray();
    }

    private static double mean(double[] v) {
        return Arrays.stream(v).average().orElse(0.0);
    }

    private static double std(double[] v) {
        if (v.length < 2) {
            return 0.0;
        }
        double m = mean(v);
        double s = 0.0;
        for (double x : v) {
            s += (x - m) * (x - m);
        }
        return Math.sqrt(s / (v.length - 1));
    }
}

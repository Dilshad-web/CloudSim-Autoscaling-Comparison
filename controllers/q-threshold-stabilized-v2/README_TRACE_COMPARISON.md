# Q-Threshold — raw Bitbrains 30-day comparison mode

This version reads the raw Bitbrains fastStorage trace files directly. It no longer needs the old `datasets/batch_001.csv` … `batch_025.csv` aggregated files.

## Shared setup with S-Threshold

The new runner is aligned with `TraceBatchRunner` in the supplied S-Threshold project for the following experiment inputs:

- Raw trace directory: `data/bitbrains/fastStorage/2013-8/`
- All 1,250 raw CSV files (no subset by default)
- 288 trace steps per simulated day; 30 days are selected from 8,640 steps
- The final endpoint sample, if present, is excluded just as in S-Threshold
- One global trace P95 normalization, mapping P95 to 90% of the baseline pool's capacity
- 10 baseline VMs, 750 MIPS per VM, 1,000 MI cloudlets
- 10 simulated seconds per trace step and a 2-second controller decision interval
- The same trace-to-cloudlet arrival formula as S-Threshold
- The same host configuration and cloudlet scheduler/resource settings
- Metrics: makespan, average response time, average waiting time, P95 response time, throughput (completed cloudlets / makespan), average VM count, VM-seconds, scale-out, scale-in, and direction reversals (flaps)
- Three repetitions per day; Q-tables and thresholds persist in memory across repetitions and days

## Important scientific limitations

This is a matched *trace-driven comparison mode*, not proof of exact reproduction of the original Q-Threshold paper. The Bitbrains aggregate CPU trace is normalized and converted to staggered synthetic cloudlet arrivals. It is not a direct replay of each original VM's task queue or CPU schedule. The existing Q-Threshold reward is project-specific and uses response time plus VM count; it is not claimed to be the original paper's exact reward. All methods must use the same metric formulas before their numeric results are compared.

Waiting time is measured as `execStartTime - submissionDelay`, response time as `finishTime - submissionDelay`, turnaround time as `finishTime - submissionDelay`, and makespan as the last cloudlet finish time measured from simulation start (the trace begins at time zero). Response and turnaround therefore coincide by definition. Throughput is `completedCloudlets / makespan`, matching the S-Threshold trace runner's common comparison metric, not the S-Threshold paper's separate Equation 12 throughput definition.

## VM allocation and retry safety

The runner disables CloudSim broker auto-retries for failed VM placements (`setFailedVmsRetryDelay(0)`) so an unplaceable VM cannot keep the simulation alive in a five-second retry loop. The controller permits only one outstanding VM operation, records every allocation failure, and applies a finite 45-second backoff before another scale-out attempt. A failed or unresolved VM request makes the run invalid; failures are not hidden.

The controller uses a moving-average CPU signal and queue backlog to avoid scaling in while submitted work is waiting. Q-threshold adjustments are limited to one update per 30 simulated seconds; scale-out requires two consecutive high-demand observations (or an urgent backlog), scale-in requires four consecutive low-demand observations and no submitted queue backlog. A 45-second scale-in hold follows a confirmed scale-out. These are explicit stabilization changes to this project's controller and must be disclosed in experiment reports; they are not claimed to be the exact original Q-Threshold policy.

The six-host configuration and the 40-VM active limit are retained; host resources have not been inflated to hide allocation problems. The run-level resource penalty now uses measured average VM count instead of the broker's post-shutdown active VM count.

## Run on macOS or Windows

Use Java 21 or newer (Java 23 is supported) and Maven 3.8 or newer. Extract the ZIP, open the project root in IntelliJ, and allow Maven to reload `pom.xml`. On macOS, the included shell scripts are the simplest option.

### 1. Pilot: one day

On macOS run `./run_qthreshold_pilot.sh`; on Windows run `run_qthreshold_pilot.bat`. It compiles the project, reads all raw CSVs, and runs day 1 with three repetitions. The first trace load can take several minutes because 1,250 files are parsed. It writes to `results_qthreshold/`.

### 2. Full 30-day experiment

After reviewing the pilot output, run `./run_qthreshold_30days.sh` on macOS or `run_qthreshold_30days.bat` on Windows. It resumes from the last completed day using the saved Q-tables and thresholds, so a one-day pilot can continue into the full 30-day run. It writes a per-day CSV and controller-state checkpoint after each day, plus a combined run CSV and per-day/overall summaries. To intentionally discard prior results and start over, add `-Dbitbrains.fresh=true` to the Java command in the script.

### 3. Plot the actual output

Install plotting dependencies once:

```bash
python3 -m pip install pandas matplotlib
```

Then run `python3 plot_qthreshold_trace_results.py` (or `py plot_qthreshold_trace_results.py` on Windows).

Plots are generated only from the simulation output files; no example/fabricated results are bundled as experiment data.

## Output files

- `results_qthreshold/qthreshold_30day_results.csv`: one row per day and repetition (up to 90 rows), including validation reasons, VM allocation counters, queue-backlog peak, and unresolved-operation counters
- `results_qthreshold/qthreshold_day01.csv` … `qthreshold_day30.csv`: day-level raw run records, with `VALID`/`INVALID` status and per-repetition diagnostics
- `results_qthreshold/qthreshold_batch_summary.csv`: per-day averages across repetitions
- `results_qthreshold/qthreshold_overall_summary.csv` and `.md`: mean and sample standard deviation across valid days only
- `results_qthreshold/qthreshold_batch_trace_used.csv`: raw CPU, active-VM count, and normalized offered load for each step
- `results_qthreshold/qthreshold_batch_config.md`: recorded experiment configuration and assumptions

A day is marked valid only if every requested synthetic cloudlet completed in each repetition, all metric rows are valid, no cloudlets were lost when a VM was removed, and there are no failed or unresolved VM operations. If a day is invalid, its Q-table/threshold updates are rolled back before the next day. The runner saves controller state after each day and resumes from the last complete day. Do not include invalid days in model-performance claims.

## Fair comparison protocol

Run S-Threshold and Q-Threshold with the same raw trace directory, `stepsPerDay=288`, `stepSeconds=10`, `p95Load=0.9`, and the same CloudSim VM/host/cloudlet settings. Compare the shared columns: makespan, average response time, average waiting time, P95 response time, throughput, average VM count, VM-seconds, scale-out/in counts, and flaps. Use only valid days from both projects. The S-Threshold project includes its own baseline and threshold-only modes; these are not Q-Threshold results.

## Fresh rerun after controller changes

Use `./run_qthreshold_30days_fresh.sh` on macOS or `run_qthreshold_30days_fresh.bat` on Windows. This archives the existing `results_qthreshold/` directory and starts from Day 1 with a new controller settings signature. Use the ordinary `run_qthreshold_30days.sh` only to resume an interrupted run with the exact same settings.

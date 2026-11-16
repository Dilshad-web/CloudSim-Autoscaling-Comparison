# Bitbrains batch configuration

Written at the start of every run of TraceBatchRunner. Real-workload evaluation, run after the paper-aligned baseline; it does not reproduce the paper's synthetic benchmark.

```
Dataset directory:        data\bitbrains\fastStorage\2013-8
VM files:                 1250 (folder has 1250 CSV files)
Total trace steps:        8641 (8640 simulated, 1 trailing endpoint sample(s) not simulated)
Full-trace mean CPU:      7.2054 % (simulated steps; all samples: 7.2054 %)
Full-trace P95:           12.4129 % (simulated steps; all samples: 12.4129 %)
Global load scale:        x7.2505
Target P95:               90 % of the capacity of the baseline VM pool
Steps per day:            288
Number of days:           30 (this run: 1)
```

- One global load scale for all days: the P95 of the simulated trace is mapped to the stated share of the capacity of the baseline VM pool (not recalculated per day).
- Every day: STATIC, THRESHOLD_ONLY and 3 SARSA repetitions receive exactly the same offered-load sequence.
- Days run in order; the SARSA Q-table carries over from day to day and is saved after every valid day.
- A day counts only if every run finished all of its cloudlets.
- Raw and scaled load of every step: results/bitbrains_batch_trace_used.csv

Settings signature (results are only resumed when this is identical):

```
dir=data\bitbrains\fastStorage\2013-8;csvFiles=1250;vms=0;stepsPerDay=288;samples=8641;globalScale=7.2505494290;baselineVms=10;mips=750;cloudletLength=1000;decisionInterval=2.0;sarsaRepetitions=3;alpha=0.1;gamma=0.9;epsilon=0.1;stepSeconds=10.0;targetP95Load=0.9
```

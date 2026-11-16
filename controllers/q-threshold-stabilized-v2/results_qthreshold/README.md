# Q-Threshold stabilized-v2 result snapshot

This directory contains the selected 30-day stabilized-v2 trace-run outputs used in the manuscript. It includes the run configuration/signature, overall and per-day summaries, per-day run CSVs, and controller-state checkpoints. The raw Bitbrains files, trace cache, and derived per-step offered-load export are not included.

- Valid days: 30/30
- Repetitions per day: 3
- Q-table and threshold state persist across repetitions and days; the three repetitions are not independent replications.
- The configuration identifies the controller as `Q-Threshold-Stabilized-v2`.

See `qthreshold_batch_config.md`, `qthreshold_settings.signature`, and `qthreshold_overall_summary.md`.

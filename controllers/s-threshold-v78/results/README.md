# S-Threshold v78 result snapshot

This directory contains the selected 30-day v78 trace-run outputs used in the manuscript. The snapshot includes the run configuration/signature, aggregate summaries, per-day metric CSVs, and SARSA Q-table checkpoints. Historical archive directories, per-step trace/timeline exports, and the processed trace cache were intentionally omitted.

- Valid days: 30/30
- Daily run records: STATIC, THRESHOLD_ONLY, and three sequential SARSA trials
- Q-table state carries across the sequential runs/days; the three trials are not independent replications.
- No fixed random seed is recorded in the supplied settings signature.

The reported metrics are descriptive summaries across the 30 valid daily aggregates. See `bitbrains_batch_config.md`, `bitbrains_batch_signature.txt`, and `bitbrains_batch_summary.md`.

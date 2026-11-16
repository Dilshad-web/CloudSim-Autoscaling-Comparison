# Q-Threshold — stabilized-v2

This Maven project contains the supplied stabilized Q-Threshold trace implementation and the selected 30-day result snapshot in `results_qthreshold/`. Raw Bitbrains trace CSVs were intentionally excluded from the repository.

## Stabilization configuration recorded for the selected result set

- CPU exponential moving average coefficient: 0.35
- Queue-aware scale-out
- Two consecutive scale-out samples
- Four consecutive scale-in samples
- Threshold adjustment interval: 30 seconds
- Minimum action gap: 10 seconds
- Scale-in hold after confirmed scale-out: 45 seconds
- Learning parameters: α=0.1, γ=0.9, ε=0.1
- Q-table and threshold state persist across three daily repetitions and across days

The reward is the project-level Q-Threshold reward based on response time and VM count. This should not be described as the reward from the selected S-Threshold paper or as an exact reproduction of a separate published algorithm. See `COMPARISON_COMPATIBILITY.md` and `STABILITY_CHANGELOG.md`.

## Run

Requirements: Java 21+ and Maven 3.8+. Place the 1,250 fastStorage trace CSVs in `data/bitbrains/fastStorage/2013-8/` (see `../../docs/DATASET.md`).

- Windows resume/continue: `run_qthreshold_30days.bat`
- Windows fresh run: `run_qthreshold_30days_fresh.bat`
- Linux/macOS resume/continue: `./run_qthreshold_30days.sh`
- Linux/macOS fresh run: `./run_qthreshold_30days_fresh.sh`

The selected historical results in `results_qthreshold/` contain 30 valid days and 90 valid repetition records. The included result package is retained for analysis; these packaging steps do not rerun the experiment.

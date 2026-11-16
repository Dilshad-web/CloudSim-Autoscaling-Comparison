# S-Threshold + SARSA (v78 project snapshot)

This Maven project contains the Java/CloudSim Plus trace runner used for the selected S-Threshold result snapshot in `results/`. It was assembled from the supplied v78-era project archive and `v78-update-2.zip`; see `../../docs/SOURCE_PROVENANCE.md`.

## Controller behavior in the supplied trace runner

- CPU state thresholds: LOW below 30%, MEDIUM from 30% through 70%, HIGH above 70%.
- The SARSA agent uses epsilon-greedy selection over the action values. The threshold gate then permits a scale-in only in LOW, a scale-out only in HIGH, and no scaling in MEDIUM. If the SARSA-selected action conflicts with the threshold direction, the applied action becomes `NO_ACTION`.
- The SARSA transition tracker records the agent-selected action (`sarsaAction`) for its state-action update; that is not necessarily the action applied after threshold gating or VM feasibility checks.
- In the trace runner, the reward helper is called with waiting backlog plus one, so the effective trace reward is `1 / (waiting-backlog + 1)`.
- Learning parameters in the selected configuration: α=0.1, γ=0.9, ε=0.1. The settings signature does not record a fixed random seed.

## Run

Requirements: Java 21+ and Maven 3.8+. Download the GWA-T-12 fastStorage trace separately and place its 1,250 CSV files in `data/bitbrains/fastStorage/2013-8/`. See `../../docs/DATASET.md`.

- Windows, resume/continue: `run_batch.bat`
- Windows, archive old outputs and start fresh: `start_fresh.bat`
- Linux/macOS, resume/continue: `./run_batch.sh`
- Linux/macOS, archive old outputs and start fresh: `./start_fresh.sh`

The selected historical result snapshot is included in `results/`. The fresh-start scripts move existing result output to an archive folder before running, so use them deliberately. The packaging environment did not have Maven installed; these convenience Maven-based launch scripts have not been executed here.

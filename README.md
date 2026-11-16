
# CloudSim Autoscaling Comparison

A comparative study of four horizontal auto-scaling controllers implemented using CloudSim Plus.

## Controllers

1. **STATIC** – Fixed allocation of 10 virtual machines.
2. **THRESHOLD ONLY** – CPU threshold-based horizontal scaling.
3. **S-THRESHOLD + SARSA** – Threshold-guided SARSA implementation (v78).
4. **Q-THRESHOLD** – Stabilized Q-learning threshold implementation (stabilized-v2).

## Repository structure

```text
CloudSim-Autoscaling-Comparison/
├── controllers/
│   ├── s-threshold-v78/
│   └── q-threshold-stabilized-v2/
├── results/
├── docs/
├── figures/
├── paper/
├── legacy/
└── README.md
```

## Experimental workload

The experiments use the GWA-T-12 Bitbrains fastStorage workload.

- 1,250 VM trace files
- 30 simulated days
- 8,640 trace steps
- Global P95-based workload calibration
- CloudSim Plus 7.2.0

The original trace observations are transformed into staggered synthetic Cloudlet arrivals. This is not a direct replay of original application tasks.

**Dataset note:** Raw Bitbrains CSV files are not included in this repository. Obtain the dataset from its official archive and follow its acknowledgement and citation requirements. See `docs/DATASET.md`.

## Results

The repository contains the supplied 30-day experiment outputs for both learning-based controllers and a consolidated four-controller comparison.

See:

- `results/four_controller_summary.csv`
- `controllers/s-threshold-v78/results/`
- `controllers/q-threshold-stabilized-v2/results_qthreshold/`

The three learning trials are sequential and preserve controller learning state as documented in their respective configurations. Reported results are historical experiment outputs, not a claim that a fresh clone reproduces the same run automatically.

## Research manuscript

The Springer Nature manuscript, figures, bibliography and compilation instructions are located in:

`paper/`

To compile the manuscript, use:

```bash
./compile.sh
```

A compatible LaTeX distribution and required Springer template packages must be installed.

## Reproducibility

Each controller has its own Maven configuration and execution instructions.

Read the controller-specific README files before running experiments. The Bitbrains dataset must be downloaded and placed in the configured data directory.

Historical result files and source provenance are documented under `docs/`.

## Original project

The initial CloudSim project is preserved under:

`legacy/original-root-project/`

## Authors

- Dilshad
- Utkarsh
- Saurabh Verma

Department of Information Technology
Netaji Subhas University of Technology, New Delhi, India.

# Stabilized Q-Threshold controller — changes in this build

## Defects addressed

1. **Threshold churn:** the earlier runner adjusted both learned thresholds at every 2-second control observation. This build retains 2-second monitoring but only chooses/applies Q-threshold adjustment actions once every 30 simulated seconds.
2. **Noisy CPU signal:** the control decision uses an exponential moving average of per-VM CPU utilization (alpha 0.35), while preserving raw mean CPU for reporting.
3. **Scale-in during queue build-up:** scale-out also responds to submitted cloudlet backlog. Scale-in requires four consecutive low-demand observations, zero queue backlog, and an actually idle VM candidate.
4. **Direction reversals:** a 10-second minimum gap between confirmed actions and a 45-second scale-in hold after a confirmed scale-out reduce immediate reversal.
5. **Failed VM creation handling:** broker auto-retry remains disabled. Failed scale-out requests are explicitly counted and receive a 45-second backoff instead of blocking future scale-out until an unrelated scale-in.
6. **Missing validation detail:** each repetition CSV now includes failed VM requests, scale-out requests and failures, invalid metric rows, unresolved scale operations, peak queue backlog, and a machine-readable validation reason.
7. **Reward resource term:** the VM-count penalty now uses measured average VM count, not the VM count after broker shutdown.

## Experiment settings intentionally preserved

- CloudSim Plus 7.2.0
- 1,250 raw Bitbrains fastStorage 2013-8 trace files
- 288 steps/day; 10 seconds/step; 30 days; three repetitions/day
- 10 baseline VMs; 750 MIPS; 1 PE/VM; 512 MB RAM; 1,000 MI cloudlets
- Existing six-host hardware configuration, VM resource settings, trace normalization, and cloudlet arrival generator

## Interpretation

This is a **stabilized Q-Threshold variant**. The added filters and cooldowns change the controller policy; do not describe these results as an exact reproduction of an unmodified Q-Threshold algorithm. The full 30-day experiment must still be executed and validated on the target machine. The project does not guarantee 30/30 valid days until those empirical checks pass.

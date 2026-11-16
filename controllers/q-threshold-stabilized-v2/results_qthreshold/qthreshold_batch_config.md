# Q-Threshold raw Bitbrains experiment

Uses the same raw trace directory and global normalization policy as the S-Threshold batch runner.

- Simulated days available: 30 (this run's day limit: 30)
- Steps per day: 288
- Trailing endpoint samples excluded: 1
- Repetitions per day: 3
- Q-table and threshold state persist across repetitions and days; state is saved after each day for resuming.
- CPU trace is transformed to staggered synthetic cloudlet arrivals; it is not a direct replay of per-VM CPU scheduling.
- Controller stabilization: EMA CPU signal, queue-aware scale-out, 2 consecutive scale-out samples, 4 consecutive scale-in samples, 30-second threshold adjustment interval, 10-second minimum action gap, and a 45-second scale-in hold after confirmed scale-out.
- Reward is the existing project-level Q-Threshold reward based on response time and VM count; it is not claimed to be the original paper's exact reward.

Settings signature:

```
data=data/bitbrains/fastStorage/2013-8;files=1250;stepsPerDay=288;steps=8640;globalP95=12.4128524165;scale=7.2505494290;targetP95Load=0.9;baselineVms=10;mips=750;cloudletLength=1000;stepSeconds=10.0;decisionInterval=2.0;repetitions=3;alpha=0.1;gamma=0.9;epsilon=0.1;controller=Q-Threshold-Stabilized-v2;thresholdAdjustmentInterval=30.0;cpuEmaAlpha=0.35;scaleOutConfirmSamples=2;scaleInConfirmSamples=4;minimumActionGap=10.0;scaleInHoldAfterScaleOut=45.0
```

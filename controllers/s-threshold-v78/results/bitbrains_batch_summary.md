# Bitbrains 30-day real-workload evaluation

This is the real-workload evaluation after the paper-aligned baseline. It does not reproduce the paper's synthetic benchmark.

- Planned days: 30
- Finished days: 30
- **Valid days used: 30**
- Invalid days: 0

All values are mean +/- standard deviation over the valid days. Per day, a mode's value is the mean of its repetitions. Times are CloudSim seconds. Response and waiting time are measured from each cloudlet's arrival; throughput is completed cloudlets divided by makespan.

| Mode | Avg response | P95 response | Avg waiting | Avg VMs | VM-seconds | Makespan | Throughput/s | Scale-out | Scale-in | Flaps |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| STATIC | 4.960 +/- 10.630 | 12.142 +/- 27.472 | 3.552 +/- 10.628 | 10.000 +/- 0.000 | 28844.9 +/- 129.9 | 2884.6 +/- 13.0 | 3.90992 +/- 1.25096 | 0.000 +/- 0.000 | 0.000 +/- 0.000 | 0.000 +/- 0.000 |
| THRESHOLD_ONLY | 1.595 +/- 0.007 | 2.200 +/- 0.027 | 0.189 +/- 0.004 | 13.306 +/- 4.168 | 38336.2 +/- 12009.0 | 2881.2 +/- 0.1 | 3.91642 +/- 1.26012 | 29.133 +/- 15.688 | 26.467 +/- 15.965 | 15.333 +/- 16.352 |
| S_THRESHOLD_SARSA | 1.595 +/- 0.007 | 2.193 +/- 0.025 | 0.188 +/- 0.006 | 13.538 +/- 4.021 | 39006.0 +/- 11583.5 | 2881.3 +/- 0.4 | 3.91634 +/- 1.26017 | 23.333 +/- 12.210 | 20.411 +/- 12.928 | 10.722 +/- 9.321 |

## Paired comparison per valid day: S_THRESHOLD_SARSA minus THRESHOLD_ONLY

Negative = SARSA is lower (for times, VM-seconds and flaps, lower is better). The same days are compared, so day-to-day differences in load cancel out.

| Metric | Mean difference +/- SD | Days SARSA lower | Days equal | Days SARSA higher |
|---|---:|---:|---:|---:|
| avg_response | -0.000 +/- 0.006 | 19 | 1 | 10 |
| p95_response | -0.007 +/- 0.029 | 18 | 2 | 10 |
| avg_waiting | -0.001 +/- 0.005 | 19 | 1 | 10 |
| makespan | 0.1 +/- 0.4 | 7 | 11 | 12 |
| vm_seconds | 669.8 +/- 1458.1 | 13 | 1 | 16 |
| avg_vms | 0.232 +/- 0.506 | 13 | 1 | 16 |
| flaps | -4.611 +/- 14.264 | 21 | 5 | 4 |

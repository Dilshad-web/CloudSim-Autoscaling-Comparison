# Q-Threshold 30-day overall summary

- Valid days included: 30
- Invalid days excluded from metric averages: 0
- Repetitions per day: 3

| Metric | Mean across valid days | Sample standard deviation |
|---|---:|---:|
| Makespan | 2881.249763 | 0.157010 |
| Average response time | 1.609526 | 0.012386 |
| Average waiting time | 0.200726 | 0.016933 |
| Average turnaround time | 1.609526 | 0.012386 |
| P95 response time | 2.236676 | 0.071711 |
| Throughput (completed cloudlets / makespan) | 3.916387 | 1.260133 |
| Average VM count | 12.785287 | 3.932633 |
| VM-seconds | 36836.584194 | 11331.110649 |
| Reward (custom Q-Threshold reward) | 1.806992 | 0.009343 |

Metrics use the same definitions as the S-Threshold trace runner for makespan, response time, waiting time, P95 response time, throughput, VM-seconds, and average VM count. Turnaround time is additionally reported as finish time minus arrival time.

This summary is generated from the actual simulation outputs; no results are fabricated. A zero valid-day count means the experiment needs debugging before comparison.

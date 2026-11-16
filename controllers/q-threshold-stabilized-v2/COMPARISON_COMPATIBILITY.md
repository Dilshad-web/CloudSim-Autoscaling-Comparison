# Q-Threshold / S-Threshold comparison compatibility

| Setting or metric | S-Threshold batch runner | Q-Threshold trace runner in this project | Compatibility |
|---|---|---|---|
| Raw input | Bitbrains fastStorage 2013-8 | Same raw folder and files | Matched |
| Raw file count | All 1,250 CSVs by default | All 1,250 CSVs by default; refuses an accidental subset | Matched |
| Day definition | 288 trace steps | 288 trace steps | Matched |
| Number of days | 30 from 8,640 steps (trailing endpoint excluded) | Same | Matched if trace has 8,640 usable steps |
| CPU aggregation | Mean CPU% across VMs reporting at each trace step | Same `BitbrainsTraceLoader` | Matched |
| Global normalization | Global P95 mapped to 90% baseline capacity | Same formula and default target | Matched |
| Cloudlet arrival generator | Offered load × 10 baseline VMs × 10 seconds × 750 MIPS / 1,000 MI, fractional carry | Same formula | Matched |
| Cloudlet arrival pattern | Staggered over each 10-second trace step | Same | Matched |
| Host setup | 6 hosts, 4 PEs/host, same host capacities and VM scheduler | Copied from S-Threshold project | Matched |
| VM settings | 750 MIPS, 1 PE, 512 MB RAM, 1,000 BW, 1,000 size, space-shared scheduler | Same values | Matched |
| Decision observation interval | 2 simulated seconds | 2 simulated seconds | Matched; Q controller additionally filters observations and requires persistence before action |
| Repetitions/day | 3 SARSA repetitions | 3 Q-Threshold repetitions | Same count, different controller/learning algorithm |
| Learning | SARSA controller | Q-learning updates to lower/upper threshold Q-tables, with a 30-second threshold update interval | Intentionally different; Q stabilization safeguards must be disclosed |
| Makespan | Last cloudlet finish time from trace start | Same | Matched |
| Average response | Finish − arrival/submission delay | Same | Matched |
| Average waiting | Execution start − arrival/submission delay | Same | Matched |
| P95 response | P95 of finish − arrival | Same | Matched |
| Throughput used for trace comparison | Completed cloudlets / makespan | Same | Matched; not paper Eq. 12 throughput |
| Average VM count | VM-seconds / elapsed run time | Same | Matched |
| VM-seconds | Sum(active VMs × elapsed time) | Same | Matched |
| Scale-out, scale-in, flaps | Counted at controller decisions | Same definitions | Matched |
| Turnaround | Not included in S batch summary | Additional column: finish − arrival | Extra Q-only metric; omit it from paired comparisons unless S output adds it |
| Reward | SARSA reward based on backlog in this runner | Existing custom Q-Threshold reward based on response time and VM count | Not comparable as a metric; algorithm-specific |

## Interpretation cautions

1. This is a common trace-driven simulation protocol, not an exact reproduction of either paper's original synthetic experiment.
2. The Bitbrains aggregate CPU series is converted to synthetic cloudlet arrivals. It is not a direct replay of every original VM's task queue.
3. Response time and turnaround time are equal under the selected definitions because both are finish time minus arrival. Waiting time is different: execution start minus arrival.
4. Compare only days marked `VALID` in both projects, and compare the shared metric definitions. Do not compare reward values across the two different controllers.
5. This source package has not been compiled or executed in the current environment because Maven is unavailable and the available Java runtime is 21 while the project targets Java 23. Run the included pilot batch on a Java 23 + Maven machine before relying on results.


## Stabilized Q-Threshold variant note

The current Q-Threshold runner uses a CPU exponential moving average, queue-aware scale-out, consecutive-sample confirmation, an action cooldown, and a scale-in hold-down. These safeguards were added after trace runs showed repeated scale-in/scale-out reversals and long queueing delays. They alter the controller policy relative to a basic threshold-adjusting Q-learning implementation; comparisons should label this build as the **stabilized Q-Threshold variant**. The underlying raw Bitbrains inputs, global P95 normalization, cloudlet arrival generator, hosts, VM resource sizes, and metric definitions are retained. The reward's VM resource term uses measured average VMs rather than post-shutdown active VM count.

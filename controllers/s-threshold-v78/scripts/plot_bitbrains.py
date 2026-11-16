#!/usr/bin/env python3
"""Plots the Bitbrains trace experiment: trace load, simulated CPU% and VM count per mode.

Input : results/bitbrains_timeline.csv  (written by TraceBenchmarkRunner)
Output: results/plots/bitbrains_behaviour.png
"""
import os
import sys
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

TIMELINE = sys.argv[1] if len(sys.argv) > 1 else "results/bitbrains_timeline.csv"
OUT = sys.argv[2] if len(sys.argv) > 2 else "results/plots/bitbrains_behaviour.png"

df = pd.read_csv(TIMELINE)
df = df[df["repetition"] == 1]
modes = [m for m in ["STATIC", "THRESHOLD_ONLY", "S_THRESHOLD_SARSA"] if m in set(df["mode"])]
scaling_modes = [m for m in modes if m != "STATIC"]

fig, axes = plt.subplots(3, 1, figsize=(12, 10), sharex=True)

ref = df[df["mode"] == (scaling_modes[0] if scaling_modes else modes[0])]
axes[0].plot(ref["time"], ref["trace_cpu_percent"], label="Bitbrains mean CPU % (raw)")
axes[0].plot(ref["time"], ref["offered_load_percent"], label="offered load % of baseline capacity (scaled)")
axes[0].set_ylabel("%")
axes[0].set_title("Workload taken from the trace")
axes[0].legend()

for m in scaling_modes:
    d = df[df["mode"] == m]
    axes[1].plot(d["time"], d["sim_cpu_percent"], label=m)
axes[1].axhline(30, color="gray", linestyle=":")
axes[1].axhline(70, color="gray", linestyle=":")
axes[1].set_ylabel("simulated CPU %")
axes[1].set_title("CPU utilization seen by the controller (30% / 70% thresholds dotted)")
axes[1].legend()

for m in scaling_modes:
    d = df[df["mode"] == m]
    axes[2].step(d["time"], d["vms_after"], where="post", label=m)
axes[2].set_ylabel("VMs")
axes[2].set_xlabel("simulated time (s)")
axes[2].set_title("Number of VMs over time")
axes[2].legend()

os.makedirs(os.path.dirname(OUT), exist_ok=True)
fig.tight_layout()
fig.savefig(OUT, dpi=130)
print("Saved", OUT)

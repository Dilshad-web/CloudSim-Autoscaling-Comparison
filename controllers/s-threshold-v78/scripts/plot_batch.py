#!/usr/bin/env python3
"""Bar charts of the multi-day Bitbrains results (mean +/- std over days) for a presentation.

Input : results/bitbrains_batch_summary.csv   (written by TraceBatchRunner, valid days only)
Output: results/plots/bitbrains_batch.png
"""
import os
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

SRC = "results/bitbrains_batch_summary.csv"
OUT = "results/plots/bitbrains_batch.png"

df = pd.read_csv(SRC)
df = df[df["section"] == "mode"].rename(columns={"name": "mode", "valid_days": "days"})
if df.empty:
    raise SystemExit("No data yet in " + SRC + " - wait for at least one finished day.")

days = int(df["days"].max())
modes = [m for m in ["STATIC", "THRESHOLD_ONLY", "S_THRESHOLD_SARSA"] if m in set(df["mode"])]
labels = {"STATIC": "Static\n(10 VMs)", "THRESHOLD_ONLY": "Threshold\nonly", "S_THRESHOLD_SARSA": "S-Threshold\n+ SARSA"}
panels = [
    ("avg_response", "Average response time (s)\nlower is better"),
    ("p95_response", "95th percentile response time (s)\nlower is better"),
    ("vm_seconds", "VM-seconds used (cost)\nlower is better"),
    ("flaps", "Scaling direction reversals (flaps)\nlower is better"),
]

fig, axes = plt.subplots(1, 4, figsize=(16, 4.5))
for ax, (metric, title) in zip(axes, panels):
    sub = df[df["metric"] == metric].set_index("mode").loc[modes]
    ax.bar(range(len(modes)), sub["mean"], yerr=sub["std"], capsize=5,
           color=["#9e9e9e", "#4c78a8", "#e45756"][: len(modes)])
    ax.set_xticks(range(len(modes)))
    ax.set_xticklabels([labels[m] for m in modes])
    ax.set_title(title, fontsize=10)
fig.suptitle(f"Bitbrains trace, mean +/- std over {days} day(s)")
fig.tight_layout()
os.makedirs(os.path.dirname(OUT), exist_ok=True)
fig.savefig(OUT, dpi=140)
print("Saved", OUT)

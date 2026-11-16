from pathlib import Path
import pandas as pd
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parent
RESULTS = ROOT / "results_qthreshold"
OUT = RESULTS / "plots"
OUT.mkdir(parents=True, exist_ok=True)
summary_path = RESULTS / "qthreshold_batch_summary.csv"
if not summary_path.exists():
    raise SystemExit(f"Missing {summary_path}. Run the Q-Threshold experiment first.")

df = pd.read_csv(summary_path)
df = df[df["status"] == "VALID"].copy()
if df.empty:
    raise SystemExit("No valid days found. Inspect qthreshold_dayXX.csv and the console logs before plotting.")

def plot(column, title, ylabel, filename):
    fig, ax = plt.subplots(figsize=(10, 5))
    ax.plot(df["day"], df[column], marker="o")
    ax.set_title(title)
    ax.set_xlabel("Trace day")
    ax.set_ylabel(ylabel)
    ax.grid(True, alpha=0.3)
    fig.tight_layout()
    fig.savefig(OUT / filename, dpi=160)
    plt.close(fig)

plot("mean_makespan", "Q-Threshold makespan by day", "Simulation time", "makespan_by_day.png")
plot("mean_response_time", "Q-Threshold average response time by day", "Response time", "response_time_by_day.png")
plot("mean_waiting_time", "Q-Threshold average waiting time by day", "Waiting time", "waiting_time_by_day.png")
plot("mean_p95_response_time", "Q-Threshold P95 response time by day", "P95 response time", "p95_response_time_by_day.png")
plot("mean_throughput_cloudlets_per_s", "Q-Threshold throughput by day", "Completed cloudlets / makespan", "throughput_by_day.png")
plot("mean_avg_vms", "Q-Threshold average VM count by day", "Average active VMs", "average_vms_by_day.png")
plot("mean_vm_seconds", "Q-Threshold VM-seconds by day", "VM-seconds", "vm_seconds_by_day.png")
print(f"Saved plots to {OUT}")

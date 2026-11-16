from pathlib import Path
from base64 import b64encode
import html
import pandas as pd
import matplotlib.pyplot as plt

ROOT = Path(__file__).resolve().parents[1]
RESULTS = ROOT / "results"
PLOTS = RESULTS / "plots"
PLOTS.mkdir(parents=True, exist_ok=True)

CSV = RESULTS / "research_summary.csv"
DECISION_CSV = RESULTS / "autoscaling_decisions.csv"
QTABLE_MD = RESULTS / "sarsa_q_table.md"

if not CSV.exists():
    raise SystemExit("Run the Java project first: results/research_summary.csv not found.")

df = pd.read_csv(CSV)

metric_specs = [
    ("makespan", "Makespan", "lower is better"),
    ("response_time", "Response Time", "lower is better"),
    ("waiting_time", "Waiting Time", "lower is better"),
    ("turnaround_time", "Average Turnaround Time", "lower is better"),
    ("throughput", "Throughput", "higher is better"),
]

plot_paths = {}
for experiment in ["Cloudlets", "MIPS", "VMs"]:
    subset = df[df["experiment"] == experiment].sort_values("value")
    x = subset["value"]
    plot_paths[experiment] = []
    for column, title, direction in metric_specs:
        filename = f"{column}_vs_{experiment.lower()}.png"
        path = PLOTS / filename
        plt.figure(figsize=(8, 5))
        plt.plot(x, subset[column], marker="o")
        plt.xlabel(experiment)
        plt.ylabel(title)
        plt.title(f"{title} vs {experiment} ({direction})")
        plt.grid(True, alpha=0.25)
        plt.tight_layout()
        plt.savefig(path, dpi=180)
        plt.close()
        plot_paths[experiment].append((title, path))


def image_data_uri(path: Path) -> str:
    encoded = b64encode(path.read_bytes()).decode("ascii")
    return f"data:image/png;base64,{encoded}"


def table_html(frame: pd.DataFrame, columns, headers=None, precision=4) -> str:
    out = frame[columns].copy()
    if headers:
        out.columns = headers
    return out.to_html(index=False, classes="data-table", border=0, float_format=lambda x: f"{x:.{precision}f}")


def benchmark_section(experiment: str) -> str:
    subset = df[df["experiment"] == experiment].sort_values("value")
    cols = ["value", "cloudlets", "mips", "vms", "makespan", "response_time", "waiting_time", "turnaround_time", "throughput"]
    headers = [experiment, "Cloudlets", "MIPS", "VMs", "Makespan", "Response Time", "Waiting Time", "Turnaround", "Throughput"]
    cards = []
    for title, path in plot_paths[experiment]:
        cards.append(f'<div class="chart"><h3>{html.escape(title)} vs {html.escape(experiment)}</h3><img src="{image_data_uri(path)}" alt="{html.escape(title)} vs {html.escape(experiment)}"></div>')
    return f"""
    <section>
      <h2>{html.escape(experiment)} Experiment</h2>
      <div class="table-wrap">{table_html(subset, cols, headers)}</div>
      <div class="grid">{''.join(cards)}</div>
    </section>
    """


decision_html = "<p>No autoscaling decision CSV found.</p>"
if DECISION_CSV.exists():
    decisions = pd.read_csv(DECISION_CSV)
    decision_html = table_html(
        decisions,
        ["phase", "workload", "cpu_utilization_percent", "state", "threshold_action", "sarsa_action", "final_operation", "vm_before", "vm_after", "reward", "q_before", "q_after"],
        ["Phase", "Workload", "CPU Utilization (%)", "State", "Threshold Action", "SARSA Action", "Final Operation", "VM Before", "VM After", "Reward", "Q Before", "Q After"],
        precision=4,
    )

paper_text = """
<p>The supplied 2025 paper evaluates five metrics: makespan, response time, waiting time, average turnaround time and throughput.</p>
<ul>
<li>Cloudlets: 100–1000, with 10 VMs.</li>
<li>MIPS: 600–1000, with 500 Cloudlets and 10 VMs.</li>
<li>VMs: 10, 20, 30, 40, with 500 Cloudlets and 1000 MIPS.</li>
</ul>
<p>The paper's detailed comparison curves are presented graphically rather than as a numerical data table. This dashboard therefore keeps the measured CloudSim values separate from the paper's reported aggregate improvement percentages and trends.</p>
"""

qtable_html = "<p>Q-table file was not found.</p>"
if QTABLE_MD.exists():
    lines = QTABLE_MD.read_text(encoding="utf-8").splitlines()
    rows = [line for line in lines if line.startswith("|")]
    if len(rows) >= 2:
        header = [x.strip() for x in rows[0].strip("|").split("|")]
        body = []
        for row in rows[2:]:
            cells = [x.strip() for x in row.strip("|").split("|")]
            if len(cells) == len(header):
                body.append(cells)
        qtable_html = "<table class='data-table'><thead><tr>" + "".join(f"<th>{html.escape(x)}</th>" for x in header) + "</tr></thead><tbody>"
        for row in body:
            qtable_html += "<tr>" + "".join(f"<td>{html.escape(x)}</td>" for x in row) + "</tr>"
        qtable_html += "</tbody></table>"

html_doc = f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>S-Threshold + SARSA CloudSim Plus — Research Dashboard</title>
<style>
body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Arial, sans-serif; margin: 0; background: #f5f7fa; color: #1f2937; }}
.container {{ max-width: 1400px; margin: 0 auto; padding: 28px; }}
h1 {{ margin-bottom: 6px; }}
h2 {{ margin-top: 38px; border-bottom: 2px solid #ddd; padding-bottom: 8px; }}
h3 {{ font-size: 16px; margin: 0 0 10px; }}
.subtitle {{ color: #6b7280; margin-top: 0; }}
.card {{ background: white; border-radius: 12px; padding: 20px; margin: 20px 0; box-shadow: 0 2px 10px rgba(0,0,0,.07); }}
.table-wrap {{ overflow-x: auto; background: white; border-radius: 10px; box-shadow: 0 1px 6px rgba(0,0,0,.06); }}
.data-table {{ border-collapse: collapse; width: 100%; font-size: 13px; }}
.data-table th {{ background: #eef2f7; text-align: right; padding: 10px; border-bottom: 1px solid #d7dce3; white-space: nowrap; }}
.data-table td {{ text-align: right; padding: 8px 10px; border-bottom: 1px solid #e5e7eb; white-space: nowrap; }}
.data-table th:first-child, .data-table td:first-child {{ text-align: left; }}
.grid {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(420px, 1fr)); gap: 18px; margin-top: 18px; }}
.chart {{ background: white; padding: 14px; border-radius: 10px; box-shadow: 0 1px 6px rgba(0,0,0,.06); }}
.chart img {{ width: 100%; height: auto; display: block; }}
.note {{ background: #fffbea; border-left: 4px solid #d1a000; padding: 12px 16px; }}
.footer {{ color: #6b7280; font-size: 12px; margin-top: 32px; }}
</style>
</head>
<body>
<div class="container">
<h1>S-Threshold + SARSA CloudSim Plus</h1>
<p class="subtitle">Research benchmark and horizontal auto-scaling decision dashboard</p>

<div class="card">
<h2 style="margin-top:0">1. Auto-Scaling Decision Log</h2>
<p>Current workload CPU → workload state → threshold decision → SARSA action → final operation → VM count change.</p>
{decision_html}
<div class="note">CPU utilization in the decision demo is the current workload CPU demand supplied to the controller. Reward uses a controller-only estimated response-time proxy; the performance benchmark uses measured CloudSim metrics.</div>
</div>

<div class="card">
<h2 style="margin-top:0">2. SARSA Q-Table After Learning</h2>
<p>The values below are updated after each state-action transition using the SARSA rule. The final phase is treated as a terminal transition.</p>
{qtable_html}
</div>

<div class="card">
<h2 style="margin-top:0">3. Paper-Aligned Performance Results</h2>
{paper_text}
</div>

{benchmark_section("Cloudlets")}
{benchmark_section("MIPS")}
{benchmark_section("VMs")}

<div class="card">
<h2>4. Interpretation</h2>
<ul>
<li>Increasing Cloudlets increases makespan, response time, waiting time and turnaround time, while throughput decreases.</li>
<li>Increasing MIPS decreases execution-time metrics and increases throughput.</li>
<li>Increasing VMs decreases execution-time metrics and increases throughput.</li>
<li>The autoscaling demonstration shows the controller responding to LOW, MEDIUM and HIGH CPU states with SCALE_IN, NO_ACTION and SCALE_OUT decisions.</li>
</ul>
</div>

<p class="footer">Generated from results/research_summary.csv and results/autoscaling_decisions.csv. Graph images are embedded in this HTML file, so the dashboard can be opened directly in a browser.</p>
</div>
</body>
</html>
"""

# Keep the simple table export as a separate artifact for spreadsheet/browser inspection.
df.to_html(RESULTS / "research_table.html", index=False, float_format=lambda x: f"{x:.6f}")
(RESULTS / "research_results.html").write_text(html_doc, encoding="utf-8")

print(f"Created 15 plots in {PLOTS}")
print(f"Created dashboard: {RESULTS / 'research_results.html'}")
print(f"Created raw table HTML: {RESULTS / 'research_table.html'}")

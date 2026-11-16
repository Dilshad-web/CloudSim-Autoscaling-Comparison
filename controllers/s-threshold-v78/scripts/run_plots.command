#!/bin/bash
cd "$(dirname "$0")/.."
python3 -m pip install -r requirements.txt
python3 scripts/plot_results.py
open results/research_results.html

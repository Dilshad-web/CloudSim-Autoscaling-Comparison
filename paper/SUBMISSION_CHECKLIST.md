# Final Springer manuscript checklist

This manuscript is a research-paper draft prepared from the source code and experiment-result archives supplied by the team. The existing experiment outputs are being used; **the experiments do not need to be rerun for this manuscript revision**.

## Project and source/result consistency

- [x] Use the v78 S-Threshold source history and `v78-update-2` patch as the implementation basis for the selected S-Threshold result package.
- [x] Use the `QThreshold-CloudSim-Plus-30Day-Stability-Fix` source package as the implementation basis for the `Q-Threshold-Stabilized-v2` result package.
- [x] Update the S-Threshold methodology to match v78: SARSA chooses among all three actions; a separate threshold gate accepts only the matching LOW/HIGH direction; Q updates retain the agent-selected action.
- [x] Describe the actual S-Threshold reward as `1 / (waiting-list backlog + 1)`, not inverse response time.
- [x] Update the Q-Threshold description to reflect the stabilizers found in the supplied stabilized-v2 source archive and result signature.
- [x] Preserve the supplied four-controller result metrics, daily comparisons, figures and tables; do not mix the later v79.1 output figures with the v78 result set.
- [x] Retain the caveat that historical result packages have no executable build hash or source commit identifier. The S-Threshold signature also does not record a fixed random seed.

## Author details and declarations

- [x] Enter author order: Dilshad, Utkarsh, Saurabh Verma.
- [x] Enter the supplied institutional email addresses and mark Dilshad as corresponding author.
- [x] Enter the shared affiliation: Department of Information Technology, Netaji Subhas University of Technology, New Delhi, India.
- [x] Record the team-provided author contribution split.
- [x] Record: “This research received no external funding.”
- [x] Record: “The authors declare no competing interests.”
- [x] Acknowledge Prof. Mohit Sajwan for suggesting the Bitbrains dataset and experimental direction.
- [x] Acknowledge Bitbrains IT Services Inc. and the Grid Workloads Archive, and cite the dataset paper.
- [ ] Have all authors verify the final author metadata, contribution statement and declarations.
- [ ] Confirm Prof. Mohit Sajwan's preferred formal title/acknowledgement wording before submission.

## Data and references

- [x] Identify the dataset as the GWA-T-12 Bitbrains fastStorage trace obtained through the Grid Workloads Archive.
- [x] Use the official dataset page: https://atlarge-research.com/gwa-t-12/.
- [x] Preserve the limitation that CPU measurements are transformed into synthetic Cloudlet arrivals; original application execution is not replayed.
- [x] Cross-check the bibliography metadata against publisher or official venue records (including journal/conference DOI records, official Kubernetes documentation, and the Sutton-Barto textbook record).
- [ ] Confirm the target venue's article type, page limits, formatting requirements and reference style.
- [ ] Confirm the dataset's redistribution terms before placing any raw trace files in the public repository.

## Final manuscript and PDF

- [x] Compile using the included `sn-jnl` class and `sn-mathphys-num` bibliography style; no fatal LaTeX errors or unresolved citations/references remain.
- [x] Render all 24 pages and inspect the page overview plus title page, controller diagram/algorithm, results tables/charts, declarations, and bibliography pages for clipping or overlap.
- [x] Confirm all figures, tables, displayed equations, and the algorithm are cross-referenced in the manuscript.
- [ ] Check title, abstract, keywords, section structure and grammar.
- [x] Scan the manuscript for author and generic completion placeholders. The GitHub URL is intentionally deferred until repository creation.
- [ ] Obtain final approval from all three authors and the supervisor, as required by the department/venue.

## GitHub — final step

- [ ] Create the public repository only after manuscript and source files are verified.
- [ ] Include S-Threshold v78 source + `v78-update-2` patch, Q-Threshold stabilized-v2 source, configurations, result summaries and figure-generation materials.
- [ ] Include README instructions, software versions, and the known provenance/seed limitations.
- [ ] Do not upload raw Bitbrains trace files unless the dataset terms explicitly permit redistribution; link to the official dataset page instead.
- [ ] Add the public GitHub URL to the Code availability section and compile the final PDF once more.

## Reproducibility caveats to retain

- The supplied historical outputs do not include executable build hashes or commit identifiers.
- The v78 S-Threshold source initializes a random generator without a fixed seed, and its settings signature does not record one.
- The three S-Threshold runs per day and the three Q-Threshold runs per day share continuing learning state; they are not independent replications. Daily aggregate summaries are descriptive.
- The study uses CloudSim Plus simulation and trace-derived synthetic Cloudlets; it does not report production measurements, monetary billing cost or energy consumption.

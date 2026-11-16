# Dataset acquisition and citation

The experiments use the **GWA-T-12 Bitbrains fastStorage** trace. The fastStorage subset contains 1,250 VM trace files. The original trace files are **not included** in this repository; obtain them from the Grid Workloads Archive and comply with the provider's terms.

Official dataset page: https://atlarge-research.com/gwa-t-12/

Download the fastStorage archive from that page, extract the 1,250 CSV files, and place them at the following relative path in **each** controller project:

```text
controllers/s-threshold-v78/data/bitbrains/fastStorage/2013-8/
controllers/q-threshold-stabilized-v2/data/bitbrains/fastStorage/2013-8/
```

The dataset provider's page states that the trace was provided by Bitbrains IT Services Inc. and requires acknowledgement of the data source in published material; it also asks users to refer to the CCGrid 2015 paper and consider acknowledging the Grid Workloads Archive. See the official page before redistributing any raw or processed trace data.

Recommended dataset reference:

Shen, S., van Beek, V., and Iosup, A. “Statistical Characterization of Business-Critical Workloads Hosted in Cloud Datacenters.” 2015 IEEE/ACM 15th International Symposium on Cluster, Cloud and Grid Computing (CCGrid), pp. 465–474, 2015. DOI: https://doi.org/10.1109/CCGrid.2015.60.

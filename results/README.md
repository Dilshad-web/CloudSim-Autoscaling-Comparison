# Comparative results

`four_controller_summary.csv` combines the 30-day daily-aggregate means and sample standard deviations from the selected S-Threshold v78 and Q-Threshold stabilized-v2 result packages. The STATIC, THRESHOLD_ONLY, and S_THRESHOLD_SARSA values come from the S-Threshold summary; the Q_THRESHOLD values come from the Q-Threshold stabilized-v2 summary.

The descriptive unit is the daily aggregate (30 valid days). Each controller has three within-day learning runs with persistent learning state; these are not independent replications. The standard deviations must not be interpreted as variation across independent algorithm seeds. See each controller's result directory for the original uploaded summary/configuration files.

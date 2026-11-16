package org.example;

/** Main entry point for the project. */
public class Main {
    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println(" S-THRESHOLD + SARSA CLOUDSIM PLUS PROJECT");
        System.out.println("============================================================");

        // First show the controller's current CPU/state/action decision.
        new AutoScalingDemo().run();

        System.out.println("\nRunning paper-aligned benchmark for performance tables.\n");
        new ResearchBenchmarkRunner().run();

        // Trace-driven behaviour study on the Bitbrains dataset (optional: runs only
        // if the CSV files were downloaded into data/bitbrains/).
        if (TraceBenchmarkRunner.isDatasetAvailable()) {
            System.out.println("\nRunning Bitbrains trace experiment.\n");
            new TraceBenchmarkRunner().run();
        } else {
            System.out.println("\nBitbrains dataset not found in " + TraceBenchmarkRunner.datasetDirectory()
                    + " - skipping the trace experiment. See data/README.md for download steps.");
        }

        System.out.println("\nProject run finished.");
        System.out.println("Decision table: results/autoscaling_decisions.md");
        System.out.println("Performance tables: results/research_results.md");
        System.out.println("Paper comparison: results/paper_reference_comparison.md");
        System.out.println("Bitbrains trace: results/bitbrains_summary.md (only if the dataset is present)");
        System.out.println("Dashboard: run python3 scripts/plot_results.py to embed tables + 15 graphs in research_results.html");
    }
}

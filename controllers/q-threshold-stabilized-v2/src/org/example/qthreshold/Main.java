package org.example.qthreshold;

/**
 * Default entry point for the raw Bitbrains 30-day compatibility experiment.
 * Run the same runner directly from IntelliJ or with the Maven exec command.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        org.example.QThresholdTraceBatchRunner.main(args);
    }
}

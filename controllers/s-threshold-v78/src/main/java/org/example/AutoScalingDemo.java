package org.example;

import org.example.simulation.RewardManager;
import org.example.simulation.SThresholdManager;
import org.example.simulation.SarsaAgent;
import org.example.simulation.ScalingAction;
import org.example.simulation.WorkloadProfile;
import org.example.simulation.WorkloadState;
import org.example.simulation.VmManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Demonstrates the complete S-Threshold + SARSA learning loop.
 *
 * CPU -> state -> threshold action -> SARSA action -> final operation
 * -> VM scaling -> reward -> next state/action -> Q update.
 *
 * The CPU values are deterministic workload demands so the decision flow is
 * easy to reproduce in a viva/demo. The reward uses the project's existing
 * inverse-response-time convention, with a simple response-time proxy for
 * this controller-only demonstration (the CloudSim benchmark has its own
 * measured response time).
 */
public final class AutoScalingDemo {
    private static final double ALPHA = 0.1;
    private static final double GAMMA = 0.9;
    private static final double EPSILON = 0.0; // deterministic presentation

    public void run() {
        SarsaAgent sarsa = new SarsaAgent(ALPHA, GAMMA, EPSILON);
        SThresholdManager controller = new SThresholdManager(sarsa);
        RewardManager rewardManager = new RewardManager();
        VmManager vmManager = new VmManager();

        int currentVmCount = 3;
        List<Decision> decisions = new ArrayList<>();
        WorkloadProfile[] phases = {
                WorkloadProfile.LOW,
                WorkloadProfile.MEDIUM,
                WorkloadProfile.HIGH,
                WorkloadProfile.MEDIUM,
                WorkloadProfile.LOW
        };

        System.out.println();
        System.out.println("============================================================");
        System.out.println(" S-THRESHOLD + SARSA AUTO-SCALING DECISION DEMO");
        System.out.println("============================================================");
        System.out.println("Thresholds: LOW < 30% | MEDIUM 30%-70% | HIGH > 70%");
        System.out.println("SARSA: alpha=" + ALPHA + ", gamma=" + GAMMA + ", epsilon=" + EPSILON);
        System.out.println();
        System.out.printf("%-5s %-8s %-9s %-13s %-13s %-13s %-9s %-9s %-10s %-10s%n",
                "Phase", "CPU", "State", "Threshold", "SARSA", "FINAL", "VM Before", "VM After", "Reward", "Q After");
        System.out.println("------------------------------------------------------------------------------------------------");

        for (int i = 0; i < phases.length; i++) {
            WorkloadProfile profile = phases[i];
            double cpu = profile.getCpuUtilization() * 100.0;
            WorkloadState state = controller.getWorkloadState(cpu);
            ScalingAction thresholdAction = controller.getThresholdAction(cpu);
            ScalingAction sarsaAction = controller.getSarsaAction(state);
            ScalingAction finalAction = controller.decideFinalAction(cpu, sarsaAction);
            int vmBefore = currentVmCount;
            int vmAfter = vmManager.applyScalingAction(vmBefore, finalAction);

            // Controller-only demonstration reward. It is not a CloudSim measured response time.
            double estimatedResponseTime =
                    (profile.getCloudletCount() * (double) profile.getCloudletLength())
                            / (vmAfter * 1000.0);
            double reward = rewardManager.calculateReward(estimatedResponseTime);
            double qBefore = sarsa.getQValue(state, sarsaAction);

            WorkloadState nextState = null;
            ScalingAction nextAction = null;
            if (i < phases.length - 1) {
                double nextCpu = phases[i + 1].getCpuUtilization() * 100.0;
                nextState = controller.getWorkloadState(nextCpu);
                nextAction = controller.getSarsaAction(nextState);
                sarsa.updateQValue(state, sarsaAction, reward, nextState, nextAction);
            } else {
                // Terminal SARSA transition: there is no future state/action.
                sarsa.updateTerminalQValue(state, sarsaAction, reward);
            }

            double qAfter = sarsa.getQValue(state, sarsaAction);

            System.out.printf(Locale.US,
                    "%-5s %-7.1f%% %-9s %-13s %-13s %-13s %-9d %-9d %-10.4f %-10.4f%n",
                    "P" + (i + 1), cpu, state, thresholdAction, sarsaAction,
                    finalAction, vmBefore, vmAfter, reward, qAfter);

            decisions.add(new Decision(i + 1, profile.name(), cpu, state,
                    thresholdAction, sarsaAction, finalAction, vmBefore, vmAfter,
                    estimatedResponseTime, reward, qBefore, qAfter));
            currentVmCount = vmAfter;
        }

        writeDecisionCsv(decisions, Path.of("results", "autoscaling_decisions.csv"));
        writeDecisionMarkdown(decisions, Path.of("results", "autoscaling_decisions.md"));
        writeQTableMarkdown(sarsa, Path.of("results", "sarsa_q_table.md"));

        System.out.println();
        System.out.println("Decision log: results/autoscaling_decisions.csv");
        System.out.println("Decision table: results/autoscaling_decisions.md");
        System.out.println("Q-table: results/sarsa_q_table.md");
        System.out.println("Reward note: demo reward uses an estimated response-time proxy; benchmark metrics are measured by CloudSim.");
        System.out.println();
        sarsa.printQTable();
    }

    private void writeDecisionCsv(List<Decision> decisions, Path file) {
        StringBuilder sb = new StringBuilder();
        sb.append("phase,workload,cpu_utilization_percent,state,threshold_action,sarsa_action,final_operation,vm_before,vm_after,estimated_response_time,reward,q_before,q_after\n");
        for (Decision d : decisions) {
            sb.append(String.format(Locale.US,
                    "%d,%s,%.1f,%s,%s,%s,%s,%d,%d,%.6f,%.6f,%.6f,%.6f%n",
                    d.phase(), d.workload(), d.cpu(), d.state(), d.thresholdAction(),
                    d.sarsaAction(), d.finalAction(), d.vmBefore(), d.vmAfter(),
                    d.estimatedResponseTime(), d.reward(), d.qBefore(), d.qAfter()));
        }
        write(file, sb.toString());
    }

    private void writeDecisionMarkdown(List<Decision> decisions, Path file) {
        StringBuilder sb = new StringBuilder();
        sb.append("# S-Threshold + SARSA Auto-Scaling Decisions\n\n");
        sb.append("The controller follows: **CPU → State → Threshold → SARSA → Final Operation → VM Scaling → Reward → Q Update**.\n\n");
        sb.append("| Phase | Workload | CPU | State | Threshold | SARSA | Final Operation | VM Before | VM After | Reward | Q Before | Q After |\n");
        sb.append("|---:|---|---:|---|---|---|---|---:|---:|---:|---:|---:|\n");
        for (Decision d : decisions) {
            sb.append(String.format(Locale.US,
                    "| %d | %s | %.1f%% | %s | %s | %s | **%s** | %d | %d | %.4f | %.4f | %.4f |%n",
                    d.phase(), d.workload(), d.cpu(), d.state(), d.thresholdAction(),
                    d.sarsaAction(), d.finalAction(), d.vmBefore(), d.vmAfter(),
                    d.reward(), d.qBefore(), d.qAfter()));
        }
        sb.append("\n**Reward note:** the controller demo uses an estimated response-time proxy only to demonstrate the SARSA learning loop. The performance benchmark uses measured CloudSim response times.\n");
        write(file, sb.toString());
    }

    private void writeQTableMarkdown(SarsaAgent sarsa, Path file) {
        StringBuilder sb = new StringBuilder();
        sb.append("# SARSA Q-Table\n\n");
        sb.append(String.format(Locale.US, "Parameters: alpha = %.2f, gamma = %.2f, epsilon = %.2f\n\n", ALPHA, GAMMA, EPSILON));
        sb.append("| State | SCALE_OUT | SCALE_IN | NO_ACTION |\n|---|---:|---:|---:|\n");
        for (WorkloadState state : WorkloadState.values()) {
            sb.append(String.format(Locale.US, "| %s | %.6f | %.6f | %.6f |%n",
                    state,
                    sarsa.getQValue(state, ScalingAction.SCALE_OUT),
                    sarsa.getQValue(state, ScalingAction.SCALE_IN),
                    sarsa.getQValue(state, ScalingAction.NO_ACTION)));
        }
        sb.append("\nSARSA update: `Q(s,a) = Q(s,a) + alpha * [reward + gamma * Q(s',a') - Q(s,a)]`. The final phase uses a terminal update with no future-state term.\n");
        write(file, sb.toString());
    }

    private void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new RuntimeException("Unable to write " + file, e);
        }
    }

    private record Decision(
            int phase,
            String workload,
            double cpu,
            WorkloadState state,
            ScalingAction thresholdAction,
            ScalingAction sarsaAction,
            ScalingAction finalAction,
            int vmBefore,
            int vmAfter,
            double estimatedResponseTime,
            double reward,
            double qBefore,
            double qAfter) {}
}

package org.example.simulation;

/** Reward used by the SARSA controller. Lower response time is better. */
public class RewardManager {
    public double calculateReward(double responseTime) {
        if (responseTime < 0) {
            throw new IllegalArgumentException("Response time cannot be negative.");
        }
        return responseTime == 0 ? 1.0 : 1.0 / responseTime;
    }
}

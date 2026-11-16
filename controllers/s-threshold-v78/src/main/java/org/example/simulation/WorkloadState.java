package org.example.simulation;

/**
 * Represents the current workload state of the cloud system.
 *
 * LOW    : CPU utilization < 30%
 * MEDIUM : CPU utilization between 30% and 70%
 * HIGH   : CPU utilization > 70%
 */
public enum WorkloadState {

    LOW,
    MEDIUM,
    HIGH
}
package com.opsflow.incidents;

/** Explicit state machine: transitions cannot be chosen arbitrarily by API callers. */
public enum IncidentStatus {
    OPEN, ASSIGNED, INVESTIGATING, RESOLVED, CLOSED;

    public boolean canTransitionTo(IncidentStatus target) {
        return switch (this) {
            case OPEN -> target == ASSIGNED;
            case ASSIGNED -> target == INVESTIGATING;
            case INVESTIGATING -> target == RESOLVED;
            case RESOLVED -> target == CLOSED;
            case CLOSED -> false;
        };
    }

    public boolean isActive() { return this == OPEN || this == ASSIGNED || this == INVESTIGATING; }
}

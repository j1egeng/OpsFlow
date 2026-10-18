package com.opsflow.changes;
public enum ChangeStatus {
    DRAFT, PENDING_APPROVAL, APPROVED, REJECTED, IMPLEMENTING, COMPLETED, FAILED;
    public boolean canTransitionTo(ChangeStatus target) {
        return switch (this) {
            case DRAFT -> target == PENDING_APPROVAL;
            case PENDING_APPROVAL -> target == APPROVED || target == REJECTED;
            case REJECTED -> target == DRAFT;
            case APPROVED -> target == IMPLEMENTING;
            case IMPLEMENTING -> target == COMPLETED || target == FAILED;
            case COMPLETED, FAILED -> false;
        };
    }
}

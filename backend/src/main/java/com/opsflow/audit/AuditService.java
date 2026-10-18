package com.opsflow.audit;

import java.util.Map;

import com.opsflow.users.UserAccount;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {
    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) { this.repository = repository; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void userEvent(UserAccount actor, String action, Long targetUser, Map<String, Object> details) {
        repository.save(new AuditLog(actor, action, "USER", targetUser, details));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void serviceEvent(UserAccount actor, String action, Long targetService, Map<String, Object> details) {
        repository.save(new AuditLog(actor, action, "SERVICE", targetService, details));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void incidentEvent(UserAccount actor, String action, Long targetIncident, Map<String, Object> details) {
        repository.save(new AuditLog(actor, action, "INCIDENT", targetIncident, details));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void changeEvent(UserAccount actor, String action, Long targetChange, Map<String, Object> details) {
        repository.save(new AuditLog(actor, action, "CHANGE_REQUEST", targetChange, details));
    }
}

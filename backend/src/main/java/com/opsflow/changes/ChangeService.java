package com.opsflow.changes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import com.opsflow.audit.AuditService;
import com.opsflow.common.ApiException;
import com.opsflow.changes.ChangeDtos.*;
import com.opsflow.incidents.IncidentRepository;
import com.opsflow.incidents.IncidentService;
import com.opsflow.incidents.IncidentSeverity;
import com.opsflow.services.SoftwareServiceRepository;
import com.opsflow.users.RoleCode;
import com.opsflow.users.UserAccount;
import com.opsflow.users.UserRepository;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChangeService {
    private final ChangeRequestRepository changes;
    private final ChangeReviewRepository reviews;
    private final ChangeHistoryRepository history;
    private final SoftwareServiceRepository services;
    private final IncidentRepository incidents;
    private final IncidentService incidentService;
    private final UserRepository users;
    private final AuditService audit;
    public ChangeService(ChangeRequestRepository changes, ChangeReviewRepository reviews, ChangeHistoryRepository history,
            SoftwareServiceRepository services, IncidentRepository incidents, IncidentService incidentService, UserRepository users, AuditService audit) {
        this.changes = changes; this.reviews = reviews; this.history = history; this.services = services;
        this.incidents = incidents; this.incidentService = incidentService; this.users = users; this.audit = audit;
    }
    @Transactional
    public ChangeResponse create(DraftRequest request, long actorId) {
        var actor = actor(actorId); requireWriter(actor);
        var change = new ChangeRequest(actor); replace(change, request);
        changes.saveAndFlush(change);
        history.save(new ChangeHistory(change, null, actor, null));
        audit.changeEvent(actor, "CHANGE_CREATED", change.getId(), Map.of("serviceId", change.getService().getId()));
        return response(change, actor);
    }
    @Transactional(readOnly = true)
    public PageResponse<ChangeResponse> list(int page, int size, ChangeStatus status, RiskLevel risk, Long serviceId, Long requesterId, long actorId) {
        var actor = actor(actorId);
        Specification<ChangeRequest> filter = (root, query, builder) -> {
            var conditions = new ArrayList<Predicate>();
            if (status != null) conditions.add(builder.equal(root.get("status"), status));
            if (risk != null) conditions.add(builder.equal(root.get("riskLevel"), risk));
            if (serviceId != null) conditions.add(builder.equal(root.get("service").get("id"), serviceId));
            if (requesterId != null) conditions.add(builder.equal(root.get("requester").get("id"), requesterId));
            return builder.and(conditions.toArray(Predicate[]::new));
        };
        return PageResponse.from(changes.findAll(filter, paging(page, size)).map(change -> response(change, actor)));
    }
    @Transactional(readOnly = true)
    public ChangeResponse get(long id, long actorId) { return response(requireChange(id), actor(actorId)); }
    @Transactional
    public ChangeResponse update(long id, DraftRequest request, long actorId) {
        var actor = actor(actorId); var change = requireChange(id); requireOwner(change, actor); requireVersion(change, request.version());
        if (change.getStatus() != ChangeStatus.DRAFT && change.getStatus() != ChangeStatus.REJECTED) throw stateError();
        var previous = change.getStatus(); replace(change, request); changes.flush();
        if (previous != change.getStatus()) history.save(new ChangeHistory(change, previous, actor, null));
        audit.changeEvent(actor, "CHANGE_UPDATED", id, Map.of("status", change.getStatus()));
        return response(change, actor);
    }
    @Transactional
    public ChangeResponse submit(long id, long version, long actorId) {
        var actor = actor(actorId); var change = requireChange(id); requireOwner(change, actor); requireVersion(change, version);
        requireState(change, ChangeStatus.DRAFT); requireEnabledService(change);
        if (blank(change.getBusinessReason()) || change.getRiskLevel() == null || change.getPlannedImplementationAt() == null
                || blank(change.getImplementationPlan()) || blank(change.getRollbackPlan())) {
            throw error(400, "CHANGE_NOT_READY", "提交前必须填写业务原因、风险、计划时间、实施计划和回滚计划");
        }
        if (!change.getPlannedImplementationAt().isAfter(Instant.now())) throw error(400, "INVALID_PLANNED_TIME", "提交时的计划实施时间必须在未来");
        var previous = change.getStatus(); change.submit(); changes.flush();
        history.save(new ChangeHistory(change, previous, actor, null));
        audit.changeEvent(actor, "CHANGE_SUBMITTED", id, Map.of("submissionNumber", change.getSubmissionNumber()));
        return response(change, actor);
    }
    @Transactional
    public ChangeResponse review(long id, ReviewRequest request, long actorId) {
        var actor = actor(actorId); var change = requireChange(id);
        if (!actor.isEnabled() || !actor.getRoleCodes().contains(RoleCode.APPROVER)) throw forbidden();
        if (change.getRequester().getId().equals(actorId)) throw error(403, "SELF_APPROVAL_FORBIDDEN", "不能审批自己申请的变更");
        requireVersion(change, request.version()); requireState(change, ChangeStatus.PENDING_APPROVAL);
        if (request.decision() == ReviewDecision.REJECTED && blank(request.comment())) throw error(400, "REJECTION_COMMENT_REQUIRED", "拒绝变更时必须填写原因");
        var previous = change.getStatus(); change.review(request.decision()); changes.flush();
        reviews.save(new ChangeReview(change, actor, request.decision(), clean(request.comment())));
        history.save(new ChangeHistory(change, previous, actor, clean(request.comment())));
        audit.changeEvent(actor, "CHANGE_REVIEWED", id, Map.of("submissionNumber", change.getSubmissionNumber(), "decision", request.decision()));
        return response(change, actor);
    }
    @Transactional
    public ChangeResponse start(long id, long version, long actorId) {
        var actor = actor(actorId); var change = requireChange(id); requireOwner(change, actor); requireVersion(change, version);
        requireState(change, ChangeStatus.APPROVED); requireEnabledService(change);
        var previous = change.getStatus(); change.start(); changes.flush(); history.save(new ChangeHistory(change, previous, actor, null));
        audit.changeEvent(actor, "CHANGE_STARTED", id, Map.of("status", change.getStatus()));
        return response(change, actor);
    }
    @Transactional
    public ChangeResponse finish(long id, ResultRequest request, long actorId) {
        var actor = actor(actorId); var change = requireChange(id); requireOwner(change, actor); requireVersion(change, request.version());
        requireState(change, ChangeStatus.IMPLEMENTING);
        var previous = change.getStatus(); change.finish(request.outcome(), request.result()); changes.flush();
        history.save(new ChangeHistory(change, previous, actor, null));
        if (request.outcome() == ImplementationOutcome.FAILED) {
            incidentService.createFromFailedChange(id, change.getService().getId(), actorId,
                    "Failed change #" + id + ": " + change.getTitle(), change.getImplementationResult(), IncidentSeverity.valueOf(change.getRiskLevel().name()));
        }
        audit.changeEvent(actor, "CHANGE_FINISHED", id, Map.of("outcome", request.outcome()));
        return response(change, actor);
    }
    @Transactional(readOnly = true)
    public PageResponse<ReviewResponse> reviews(long id, int page, int size) { requireChange(id); return PageResponse.from(reviews.findByChangeId(id, paging(page, size)).map(ReviewResponse::from)); }
    @Transactional(readOnly = true)
    public PageResponse<HistoryResponse> history(long id, int page, int size) { requireChange(id); return PageResponse.from(history.findByChangeId(id, paging(page, size)).map(HistoryResponse::from)); }
    private void replace(ChangeRequest change, DraftRequest request) {
        var service = services.findById(request.serviceId()).orElseThrow(() -> error(404, "SERVICE_NOT_FOUND", "软件服务不存在"));
        if (!service.isEnabled()) throw error(400, "CHANGE_SERVICE_DISABLED", "变更所关联的服务必须启用");
        var incident = request.relatedIncidentId() == null ? null : incidents.findById(request.relatedIncidentId()).orElseThrow(() -> error(404, "INCIDENT_NOT_FOUND", "事件不存在"));
        if (incident != null && !incident.getService().getId().equals(service.getId())) throw error(400, "RELATED_INCIDENT_SERVICE_MISMATCH", "关联事件必须属于同一个服务");
        change.replace(service, incident, request);
    }
    private ChangeResponse response(ChangeRequest change, UserAccount actor) {
        boolean owner = isWriter(actor) && (isAdmin(actor) || change.getRequester().getId().equals(actor.getId()));
        boolean editable = change.getStatus() == ChangeStatus.DRAFT || change.getStatus() == ChangeStatus.REJECTED;
        var permissions = new Permissions(owner && editable, owner && change.getStatus() == ChangeStatus.DRAFT,
                actor.getRoleCodes().contains(RoleCode.APPROVER) && !change.getRequester().getId().equals(actor.getId()) && change.getStatus() == ChangeStatus.PENDING_APPROVAL,
                owner && change.getStatus() == ChangeStatus.APPROVED, owner && change.getStatus() == ChangeStatus.IMPLEMENTING);
        Long failureId = change.getStatus() == ChangeStatus.FAILED ? incidents.findIdBySourceChangeId(change.getId()).orElse(null) : null;
        return ChangeResponse.from(change, failureId, permissions);
    }
    private boolean isAdmin(UserAccount actor) { return actor.getRoleCodes().contains(RoleCode.ADMINISTRATOR); }
    private boolean isWriter(UserAccount actor) { return actor.isEnabled() && (isAdmin(actor) || actor.getRoleCodes().contains(RoleCode.DEVELOPER)); }
    private void requireWriter(UserAccount actor) { if (!isWriter(actor)) throw forbidden(); }
    private void requireOwner(ChangeRequest change, UserAccount actor) { requireWriter(actor); if (!isAdmin(actor) && !change.getRequester().getId().equals(actor.getId())) throw forbidden(); }
    private void requireState(ChangeRequest change, ChangeStatus status) { if (change.getStatus() != status) throw stateError(); }
    private void requireEnabledService(ChangeRequest change) { if (!change.getService().isEnabled()) throw error(400, "CHANGE_SERVICE_DISABLED", "变更所关联的服务必须启用"); }
    private void requireVersion(ChangeRequest change, Long version) { if (version == null) throw error(400, "VERSION_REQUIRED", "修改变更必须提供当前版本"); if (change.getVersion() != version) throw error(409, "CONCURRENT_UPDATE", "记录已被更新，请刷新后重试"); }
    private ChangeRequest requireChange(long id) { return changes.findById(id).orElseThrow(() -> error(404, "CHANGE_NOT_FOUND", "变更申请不存在")); }
    private UserAccount actor(long id) { return users.findById(id).orElseThrow(() -> error(404, "USER_NOT_FOUND", "用户不存在")); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private String clean(String value) { return blank(value) ? null : value.strip(); }
    private PageRequest paging(int page, int size) { return PageRequest.of(page, size, Sort.by("id").descending()); }
    private ApiException stateError() { return error(409, "INVALID_CHANGE_STATE", "当前变更状态不允许此操作"); }
    private ApiException forbidden() { return error(403, "FORBIDDEN", "当前账号没有权限执行此操作"); }
    private ApiException error(int status, String code, String message) { return new ApiException(HttpStatus.valueOf(status), code, message); }
}

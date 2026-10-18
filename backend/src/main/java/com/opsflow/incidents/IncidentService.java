package com.opsflow.incidents;

import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import com.opsflow.audit.AuditService;
import com.opsflow.common.ApiException;
import com.opsflow.incidents.IncidentDtos.*;
import com.opsflow.services.SoftwareServiceRepository;
import com.opsflow.users.RoleCode;
import com.opsflow.users.UserAccount;
import com.opsflow.users.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@Service
public class IncidentService {
    private final IncidentRepository incidents;
    private final IncidentNoteRepository notes;
    private final IncidentHistoryRepository history;
    private final SoftwareServiceRepository services;
    private final UserRepository users;
    private final AuditService audit;
    private final EntityManager entityManager;

    public IncidentService(IncidentRepository incidents, IncidentNoteRepository notes, IncidentHistoryRepository history,
                           SoftwareServiceRepository services, UserRepository users, AuditService audit, EntityManager entityManager) {
        this.incidents = incidents; this.notes = notes; this.history = history; this.services = services;
        this.users = users; this.audit = audit; this.entityManager = entityManager;
    }

    @Transactional
    public IncidentResponse create(CreateIncidentRequest request, long actorId) {
        UserAccount actor = actor(actorId);
        requireWriter(actor);
        var service = services.findById(request.serviceId()).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SERVICE_NOT_FOUND", "软件服务不存在"));
        if (!service.isEnabled()) throw error(HttpStatus.BAD_REQUEST, "INCIDENT_SERVICE_DISABLED", "只能为启用的服务报告事件");
        Incident incident = incidents.saveAndFlush(new Incident(service, actor, request.title().strip(), request.description().strip(), request.severity()));
        history.save(new IncidentHistory(incident, null, actor, null));
        audit.incidentEvent(actor, "INCIDENT_CREATED", incident.getId(), Map.of("serviceId", service.getId(), "severity", incident.getSeverity(), "status", incident.getStatus()));
        return response(incident, actor);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long createFromFailedChange(long changeId, long serviceId, long actorId, String title, String result, IncidentSeverity severity) {
        if (incidents.countFailedChange(changeId, serviceId) != 1) throw error(HttpStatus.CONFLICT, "INVALID_CHANGE_STATE", "只有失败的同服务变更才能生成事件");
        var existing = incidents.findIdBySourceChangeId(changeId);
        if (existing.isPresent()) return existing.get();
        var actor = actor(actorId); requireWriter(actor);
        var service = services.findById(serviceId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "SERVICE_NOT_FOUND", "软件服务不存在"));
        String safeTitle = title.length() <= 200 ? title : title.substring(0, Character.isHighSurrogate(title.charAt(199)) ? 199 : 200);
        var incident = new Incident(service, actor, safeTitle, result, severity);
        incident.linkFailedChange(changeId); incidents.saveAndFlush(incident);
        history.save(new IncidentHistory(incident, null, actor, null));
        audit.incidentEvent(actor, "INCIDENT_CREATED", incident.getId(), Map.of("serviceId", serviceId, "sourceChangeId", changeId, "severity", severity, "status", IncidentStatus.OPEN));
        return incident.getId();
    }

    @Transactional(readOnly = true)
    public PageResponse<IncidentResponse> list(int page, int size, IncidentStatus status, IncidentSeverity severity, Long serviceId, Long assigneeId, long actorId) {
        UserAccount actor = actor(actorId);
        Specification<Incident> filter = (root, query, builder) -> {
            var predicates = new ArrayList<Predicate>();
            if (status != null) predicates.add(builder.equal(root.get("status"), status));
            if (severity != null) predicates.add(builder.equal(root.get("severity"), severity));
            if (serviceId != null) predicates.add(builder.equal(root.get("service").get("id"), serviceId));
            if (assigneeId != null) predicates.add(builder.equal(root.get("assignee").get("id"), assigneeId));
            return builder.and(predicates.toArray(Predicate[]::new));
        };
        return PageResponse.from(incidents.findAll(filter, descending(page, size)).map(incident -> response(incident, actor)));
    }

    @Transactional(readOnly = true)
    public IncidentResponse get(long id, long actorId) { return response(requireIncident(id), actor(actorId)); }

    @Transactional(readOnly = true)
    public PageResponse<Person> assignees(int page, int size, long actorId) {
        requireAdmin(actor(actorId));
        return PageResponse.from(users.findIncidentAssignees(PageRequest.of(page, size, Sort.by("id"))).map(Person::from));
    }

    @Transactional
    public IncidentResponse assign(long id, AssignIncidentRequest request, long actorId) {
        UserAccount actor = actor(actorId);
        requireAdmin(actor);
        Incident incident = requireIncident(id);
        requireVersion(incident, request.version());
        if (!incident.getStatus().isActive()) throw error(HttpStatus.CONFLICT, "INCIDENT_NOT_ASSIGNABLE", "已解决或关闭的事件不能重新分配");
        UserAccount assignee = actor(request.assigneeId());
        if (!assignee.isEnabled() || !isWriter(assignee)) throw error(HttpStatus.BAD_REQUEST, "INVALID_ASSIGNEE", "处理人必须是启用的管理员或开发者");
        Long previousId = incident.getAssignee() == null ? null : incident.getAssignee().getId();
        if (assignee.getId().equals(previousId)) return response(incident, actor);
        IncidentStatus previous = incident.getStatus();
        incident.assign(assignee);
        incidents.flush();
        if (previous != incident.getStatus()) history.save(new IncidentHistory(incident, previous, actor, clean(request.comment())));
        Map<String, Object> assignment = new LinkedHashMap<>();
        assignment.put("previousAssigneeId", previousId); assignment.put("assigneeId", assignee.getId());
        assignment.put("comment", clean(request.comment()));
        audit.incidentEvent(actor, "INCIDENT_ASSIGNED", id, assignment);
        return response(incident, actor);
    }

    @Transactional
    public IncidentResponse transition(long id, TransitionRequest request, long actorId) {
        UserAccount actor = actor(actorId);
        Incident incident = requireIncident(id);
        if (request.status() == IncidentStatus.CLOSED) requireAdmin(actor); else requireOperator(incident, actor);
        requireVersion(incident, request.version());
        IncidentStatus previous = incident.getStatus();
        if (request.status() == IncidentStatus.ASSIGNED || !previous.canTransitionTo(request.status())) {
            throw error(HttpStatus.CONFLICT, "INVALID_INCIDENT_TRANSITION", "不允许跳过、回退或重复事件状态");
        }
        String resolution = clean(request.resolution());
        if (request.status() == IncidentStatus.RESOLVED && resolution == null) throw error(HttpStatus.BAD_REQUEST, "RESOLUTION_REQUIRED", "解决事件必须填写解决说明");
        if (request.status() != IncidentStatus.RESOLVED && resolution != null) throw error(HttpStatus.BAD_REQUEST, "UNEXPECTED_RESOLUTION", "解决说明仅可在解决事件时填写");
        incident.transition(request.status(), resolution);
        incidents.flush();
        history.save(new IncidentHistory(incident, previous, actor, clean(request.comment())));
        audit.incidentEvent(actor, "INCIDENT_STATUS_CHANGED", id, Map.of("oldStatus", previous, "newStatus", incident.getStatus()));
        return response(incident, actor);
    }

    @Transactional
    public NoteResponse addNote(long id, AddNoteRequest request, long actorId) {
        UserAccount actor = actor(actorId);
        Incident incident = requireIncident(id);
        requireOperator(incident, actor);
        requireVersion(incident, request.version());
        if (!incident.getStatus().isActive()) throw error(HttpStatus.CONFLICT, "INCIDENT_NOT_WRITABLE", "已解决或关闭的事件不能追加备注");
        // Atomic compare-and-swap also works when a caller already updated this aggregate
        // in the same transaction; a provider lock upgrade may otherwise skip the increment.
        if (incidents.advanceVersion(id, request.version()) != 1) {
            throw error(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "记录已被更新，请刷新后重试");
        }
        entityManager.refresh(incident);
        IncidentNote note = notes.saveAndFlush(new IncidentNote(incident, actor, request.content().strip()));
        audit.incidentEvent(actor, "INCIDENT_NOTE_ADDED", id, Map.of("noteId", note.getId()));
        return NoteResponse.from(note);
    }

    @Transactional(readOnly = true)
    public PageResponse<NoteResponse> notes(long id, int page, int size) {
        requireIncident(id);
        return PageResponse.from(notes.findByIncidentId(id, descending(page, size)).map(NoteResponse::from));
    }

    @Transactional(readOnly = true)
    public PageResponse<HistoryResponse> history(long id, int page, int size) {
        requireIncident(id);
        return PageResponse.from(history.findByIncidentId(id, descending(page, size)).map(HistoryResponse::from));
    }

    private IncidentResponse response(Incident incident, UserAccount actor) {
        boolean admin = isAdmin(actor);
        boolean operator = admin || isWriter(actor) && incident.getAssignee() != null && incident.getAssignee().getId().equals(actor.getId());
        IncidentStatus state = incident.getStatus();
        return IncidentResponse.from(incident, new Permissions(admin && state.isActive(), operator && state == IncidentStatus.ASSIGNED,
                operator && state == IncidentStatus.INVESTIGATING, admin && state == IncidentStatus.RESOLVED, operator && state.isActive()));
    }
    private boolean isAdmin(UserAccount actor) { return actor.getRoleCodes().contains(RoleCode.ADMINISTRATOR); }
    private boolean isWriter(UserAccount actor) { return isAdmin(actor) || actor.getRoleCodes().contains(RoleCode.DEVELOPER); }
    private void requireAdmin(UserAccount actor) { if (!actor.isEnabled() || !isAdmin(actor)) throw forbidden(); }
    private void requireWriter(UserAccount actor) { if (!actor.isEnabled() || !isWriter(actor)) throw forbidden(); }
    private void requireOperator(Incident incident, UserAccount actor) {
        requireWriter(actor);
        if (!isAdmin(actor) && (incident.getAssignee() == null || !incident.getAssignee().getId().equals(actor.getId()))) throw forbidden();
    }
    private UserAccount actor(long id) { return users.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在")); }
    private Incident requireIncident(long id) { return incidents.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "INCIDENT_NOT_FOUND", "事件不存在")); }
    private void requireVersion(Incident incident, long version) { if (version != incident.getVersion()) throw error(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "记录已被更新，请刷新后重试"); }
    private String clean(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private PageRequest descending(int page, int size) { return PageRequest.of(page, size, Sort.by("id").descending()); }
    private ApiException forbidden() { return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "当前账号没有权限执行此操作"); }
    private ApiException error(HttpStatus status, String code, String message) { return new ApiException(status, code, message); }
}

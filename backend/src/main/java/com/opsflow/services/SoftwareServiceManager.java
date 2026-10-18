package com.opsflow.services;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.opsflow.audit.AuditService;
import com.opsflow.common.ApiException;
import com.opsflow.services.ServiceDtos.SaveServiceRequest;
import com.opsflow.services.ServiceDtos.ServicePage;
import com.opsflow.services.ServiceDtos.ServiceResponse;
import com.opsflow.users.UserAccount;
import com.opsflow.users.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SoftwareServiceManager {
    private final SoftwareServiceRepository services;
    private final UserRepository users;
    private final AuditService audit;

    public SoftwareServiceManager(SoftwareServiceRepository services, UserRepository users, AuditService audit) {
        this.services = services;
        this.users = users;
        this.audit = audit;
    }

    @Transactional
    public ServiceResponse create(SaveServiceRequest request, long actorId) {
        String code = normalizeCode(request.code());
        if (services.existsByCodeIgnoreCase(code)) {
            throw duplicateCode();
        }
        UserAccount actor = requireUser(actorId);
        SoftwareService service = services.saveAndFlush(new SoftwareService(code, request.name().strip(),
                normalizeDescription(request.description()), resolveOwner(request.ownerId()), actor));
        audit.serviceEvent(actor, "SERVICE_CREATED", service.getId(), snapshot(service));
        return ServiceResponse.from(service);
    }

    @Transactional(readOnly = true)
    public ServicePage list(int page, int size, Boolean enabled) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by("id").ascending());
        return ServicePage.from(enabled == null ? services.findAll(pageable) : services.findByEnabled(enabled, pageable));
    }

    @Transactional(readOnly = true)
    public ServiceResponse get(long id) {
        return ServiceResponse.from(requireService(id));
    }

    @Transactional
    public ServiceResponse update(long id, SaveServiceRequest request, long actorId) {
        SoftwareService service = requireService(id);
        String code = normalizeCode(request.code());
        if (services.existsByCodeIgnoreCaseAndIdNot(code, id)) {
            throw duplicateCode();
        }
        UserAccount owner = resolveOwner(request.ownerId());
        Map<String, Object> previous = snapshot(service);
        service.updateDetails(code, request.name().strip(), normalizeDescription(request.description()), owner);
        services.flush();
        audit.serviceEvent(requireUser(actorId), "SERVICE_UPDATED", id,
                Map.of("previous", previous, "current", snapshot(service)));
        return ServiceResponse.from(service);
    }

    @Transactional
    public ServiceResponse setEnabled(long id, boolean enabled, long actorId) {
        SoftwareService service = requireService(id);
        if (enabled && service.getOwner() != null) {
            requireEnabledOwner(service.getOwner());
        }
        boolean previous = service.isEnabled();
        service.setEnabled(enabled);
        services.flush();
        audit.serviceEvent(requireUser(actorId), "SERVICE_STATUS_UPDATED", id,
                Map.of("previousEnabled", previous, "enabled", enabled));
        return ServiceResponse.from(service);
    }

    private SoftwareService requireService(long id) {
        return services.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "SERVICE_NOT_FOUND", "软件服务不存在"));
    }

    private UserAccount requireUser(long id) {
        return users.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "USER_NOT_FOUND", "用户不存在"));
    }

    private UserAccount resolveOwner(Long ownerId) {
        if (ownerId == null) {
            return null;
        }
        UserAccount owner = requireUser(ownerId);
        requireEnabledOwner(owner);
        return owner;
    }

    private void requireEnabledOwner(UserAccount owner) {
        if (!owner.isEnabled()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SERVICE_OWNER_DISABLED", "服务负责人必须是启用的用户");
        }
    }

    private String normalizeCode(String code) { return code.toLowerCase(Locale.ROOT); }

    private String normalizeDescription(String description) {
        return description == null || description.isBlank() ? null : description.strip();
    }

    private ApiException duplicateCode() {
        return new ApiException(HttpStatus.CONFLICT, "SERVICE_CODE_EXISTS", "服务编码已存在");
    }

    private Map<String, Object> snapshot(SoftwareService service) {
        // LinkedHashMap intentionally permits null description/owner, unlike Map.of.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("code", service.getCode());
        details.put("name", service.getName());
        details.put("description", service.getDescription());
        details.put("ownerId", service.getOwner() == null ? null : service.getOwner().getId());
        details.put("enabled", service.isEnabled());
        return details;
    }
}

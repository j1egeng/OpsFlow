package com.opsflow.users;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.opsflow.audit.AuditService;
import com.opsflow.common.ApiException;
import com.opsflow.common.PasswordRules;
import com.opsflow.users.UserDtos.CreateUserRequest;
import com.opsflow.users.UserDtos.UserPage;
import com.opsflow.users.UserDtos.UserResponse;
import jakarta.validation.Validator;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwords;
    private final AuditService audit;
    private final Validator validator;

    public UserService(UserRepository users, RoleRepository roles, PasswordEncoder passwords,
                       AuditService audit, Validator validator) {
        this.users = users;
        this.roles = roles;
        this.passwords = passwords;
        this.audit = audit;
        this.validator = validator;
    }

    @Transactional
    public UserResponse create(CreateUserRequest request, long actorId) {
        lockAdministratorRole();
        return createInternal(request, requireUser(actorId));
    }

    @Transactional(readOnly = true)
    public UserPage list(int page, int size) {
        return UserPage.from(users.findAll(PageRequest.of(page, size, Sort.by("id").ascending())));
    }

    @Transactional(readOnly = true)
    public UserResponse current(long id) {
        return UserResponse.from(requireUser(id));
    }

    @Transactional
    public UserResponse replaceRoles(long id, Set<RoleCode> requested, long actorId) {
        lockAdministratorRole();
        UserAccount user = requireUser(id);
        protectLastAdministrator(user, user.isEnabled(), requested);
        List<RoleCode> previous = user.getRoleCodes().stream().sorted().toList();
        UserAccount actor = requireUser(actorId);
        user.replaceRoles(resolveRoles(requested), actor);
        users.flush();
        audit.userEvent(actor, "USER_ROLES_UPDATED", id,
                Map.of("previousRoles", previous, "roles", requested.stream().sorted().toList()));
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse setEnabled(long id, boolean enabled, long actorId) {
        lockAdministratorRole();
        UserAccount user = requireUser(id);
        protectLastAdministrator(user, enabled, user.getRoleCodes());
        user.setEnabled(enabled);
        users.flush();
        audit.userEvent(requireUser(actorId), "USER_STATUS_UPDATED", id, Map.of("enabled", enabled));
        return UserResponse.from(user);
    }

    @Transactional
    public boolean bootstrapAdministrator(String username, String email, String displayName, String password) {
        lockAdministratorRole();
        if (users.countEnabledWithRole(RoleCode.ADMINISTRATOR) > 0) {
            return false;
        }
        CreateUserRequest request = new CreateUserRequest(username, email, displayName, password,
                Set.of(RoleCode.ADMINISTRATOR));
        if (!validator.validate(request).isEmpty()) {
            throw new IllegalStateException("Bootstrap administrator configuration is invalid; check username, email, display name and password");
        }
        createInternal(request, null);
        return true;
    }

    private UserResponse createInternal(CreateUserRequest request, UserAccount actor) {
        PasswordRules.validateNewPassword(request.password());
        String username = request.username().trim().toLowerCase(Locale.ROOT);
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        if (users.existsByUsernameIgnoreCase(username) || users.existsByEmailIgnoreCase(email)) {
            throw new ApiException(HttpStatus.CONFLICT, "USER_ALREADY_EXISTS", "用户名或邮箱已存在");
        }
        List<Role> assignedRoles = resolveRoles(request.roles());
        UserAccount user = users.saveAndFlush(new UserAccount(username, email, request.displayName().trim(),
                passwords.encode(request.password())));
        user.replaceRoles(assignedRoles, actor);
        users.flush();
        audit.userEvent(actor, "USER_CREATED", user.getId(),
                Map.of("username", username, "roles", request.roles().stream().sorted().toList()));
        return UserResponse.from(user);
    }

    private List<Role> resolveRoles(Set<RoleCode> requested) {
        List<Role> result = roles.findByCodeIn(requested);
        if (result.size() != requested.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ROLES", "角色不存在");
        }
        return result;
    }

    private UserAccount requireUser(long id) {
        return users.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "USER_NOT_FOUND", "用户不存在"));
    }

    private void lockAdministratorRole() {
        roles.lockByCode(RoleCode.ADMINISTRATOR).orElseThrow(() ->
                new IllegalStateException("Application roles are missing; run the database migrations first"));
    }

    private void protectLastAdministrator(UserAccount user, boolean enabled, Set<RoleCode> requestedRoles) {
        if (user.isEnabled() && user.getRoleCodes().contains(RoleCode.ADMINISTRATOR)
                && (!enabled || !requestedRoles.contains(RoleCode.ADMINISTRATOR))
                && users.countEnabledWithRole(RoleCode.ADMINISTRATOR) <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, "LAST_ADMINISTRATOR",
                    "至少需要保留一个启用的管理员账号");
        }
    }
}

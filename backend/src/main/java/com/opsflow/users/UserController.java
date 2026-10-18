package com.opsflow.users;

import com.opsflow.users.UserDtos.CreateUserRequest;
import com.opsflow.users.UserDtos.UpdateRolesRequest;
import com.opsflow.users.UserDtos.UpdateStatusRequest;
import com.opsflow.users.UserDtos.UserPage;
import com.opsflow.users.UserDtos.UserResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService users;

    public UserController(UserService users) { this.users = users; }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse create(@Valid @RequestBody CreateUserRequest request, @AuthenticationPrincipal Jwt jwt) {
        return users.create(request, Long.parseLong(jwt.getSubject()));
    }

    @GetMapping
    public UserPage list(@RequestParam(defaultValue = "0") @Min(0) int page,
                         @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return users.list(page, size);
    }

    @PutMapping("/{id}/roles")
    public UserResponse roles(@PathVariable @Positive long id, @Valid @RequestBody UpdateRolesRequest request,
                              @AuthenticationPrincipal Jwt jwt) {
        return users.replaceRoles(id, request.roles(), Long.parseLong(jwt.getSubject()));
    }

    @PatchMapping("/{id}/status")
    public UserResponse status(@PathVariable @Positive long id, @Valid @RequestBody UpdateStatusRequest request,
                               @AuthenticationPrincipal Jwt jwt) {
        return users.setEnabled(id, request.enabled(), Long.parseLong(jwt.getSubject()));
    }
}

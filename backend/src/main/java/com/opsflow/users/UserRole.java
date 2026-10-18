package com.opsflow.users;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_roles")
public class UserRole {
    @EmbeddedId
    private Id id;

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private UserAccount user;

    @MapsId("roleId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id")
    private Role role;

    @Column(name = "assigned_at", nullable = false)
    private Instant assignedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by")
    private UserAccount assignedBy;

    protected UserRole() {
    }

    UserRole(UserAccount user, Role role, UserAccount assignedBy) {
        this.id = new Id(user.getId(), role.getId());
        this.user = user;
        this.role = role;
        this.assignedBy = assignedBy;
        this.assignedAt = Instant.now();
    }

    public Role getRole() { return role; }

    @Embeddable
    public static class Id implements Serializable {
        @Column(name = "user_id")
        private Long userId;
        @Column(name = "role_id")
        private Long roleId;

        protected Id() {
        }

        Id(Long userId, Long roleId) {
            this.userId = userId;
            this.roleId = roleId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Id value && Objects.equals(userId, value.userId)
                    && Objects.equals(roleId, value.roleId);
        }

        @Override
        public int hashCode() { return Objects.hash(userId, roleId); }
    }
}

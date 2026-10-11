package com.mitpass.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;

/** Reference data (V2). Users and roles are many-to-many through {@code user_role}. */
@Entity
public class Role {

    @Id
    private Short id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 16)
    private RoleName name;

    protected Role() {}

    public Short getId() {
        return id;
    }

    public RoleName getName() {
        return name;
    }
}

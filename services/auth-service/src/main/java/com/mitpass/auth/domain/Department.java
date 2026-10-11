package com.mitpass.auth.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;

/** Reference data (V2): the department a user belongs to. */
@Entity
public class Department {

    @Id
    @Column(length = 16)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    protected Department() {}

    public Department(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}

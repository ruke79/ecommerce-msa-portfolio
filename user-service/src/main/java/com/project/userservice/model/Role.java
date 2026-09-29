package com.project.userservice.model;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.project.userservice.constants.AppRole;

import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.AccessLevel;

import java.util.HashSet;
import java.util.Set;

@Setter
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(name="roles", schema="users",
         indexes = {
          @Index(columnList = "role_name", name = "idx_role_name") }         
)
public class Role{

    @Id @Tsid
    //@GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "role_id")
    private Long roleId;

    @ToString.Exclude
    @Enumerated(EnumType.STRING)
    @Column(length = 20, name = "role_name")
    private AppRole roleName;

    @OneToMany(mappedBy = "role", fetch = FetchType.LAZY, cascade = {CascadeType.MERGE})
    @JsonManagedReference // User로의 순환 참조를 관리합니다.
    @ToString.Exclude   // 양방향 관계에서 순환 참조 방지
    private Set<User> users = new HashSet<>();

    public Role(AppRole roleName) {
        this.roleName = roleName;
    }
}

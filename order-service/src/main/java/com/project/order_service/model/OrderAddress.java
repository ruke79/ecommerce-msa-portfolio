
package com.project.order_service.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.project.common.dto.order.OrderAddressDto;

import io.hypersistence.utils.hibernate.id.Tsid;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@Entity
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name="order_address", schema = "orders",
        indexes = {
                @Index(columnList = "order_id", name = "idx_order_address") },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_order_address_orderId", columnNames = {"order_id"})
        }
)
public class OrderAddress extends BaseEntity {

    
     @Id @Tsid    
    @Column(name = "order_address_id")
    private Long orderAddressId;


    private String firstname;

    private String lastname;

    @NotBlank(message="주소는 공백이 없어야 합니다.")
    @Size(min=2, message="주소1은 적어도 2자 이상이어야 합니다.")
    private String address1;

    private String address2;

    @NotBlank(message="도시명은 공백이 없어야 합니다.")
    @Size(min=2, message="도시명은 적어도 2자 이상이어야 합니다.")
    private String city;

    @NotBlank(message="도명은 공백이 없어야 합니다.")
    @Size(min=2, message="도명 적어도 2자 이상이어야 합니다.")
    private String state;

    @NotBlank(message="우편번호는 공백이 없어야 합니다.")
    //@Pattern(regexp = "(^$|[0-9]{5})", message="우편번호는 5자리 숫자이어야 합니다.")
    @Size(min=2, message="우편번호는 적어도 2자 이상이어야 합니다.")
    private String zipCode;
    
    private String country;

    private String phoneNumber;

    @OneToOne(fetch=FetchType.LAZY)
    @JoinColumn(name = "order_id", referencedColumnName = "order_id", nullable = false)
    @JsonBackReference
    private Order order;
       
    
               
}

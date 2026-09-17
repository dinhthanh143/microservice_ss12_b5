package com.vietmart.order.dto;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShippingOrderRequest {
    private String orderId;
    private String customerName;
    private String customerPhone;
    private String shippingAddress;
    private BigDecimal codAmount;
    private String idempotencyKey;
}

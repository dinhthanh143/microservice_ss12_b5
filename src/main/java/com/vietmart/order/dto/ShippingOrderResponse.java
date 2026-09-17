package com.vietmart.order.dto;

import lombok.*;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShippingOrderResponse {
    private String trackingCode;
    private String orderId;
    private String carrier;
    private BigDecimal shippingFee;
    private String status;
    private String message;
}

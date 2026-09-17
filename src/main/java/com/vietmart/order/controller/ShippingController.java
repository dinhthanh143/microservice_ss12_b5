package com.vietmart.order.controller;

import com.vietmart.order.dto.ShippingOrderRequest;
import com.vietmart.order.dto.ShippingOrderResponse;
import com.vietmart.order.service.GhtkShippingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/api/shipping")
@RequiredArgsConstructor
public class ShippingController {

    private final GhtkShippingService ghtkShippingService;

    @PostMapping("/ghtk/create")
    public ResponseEntity<ShippingOrderResponse> createShippingOrder(@RequestBody ShippingOrderRequest request) throws TimeoutException {
        return ResponseEntity.ok(ghtkShippingService.createShippingOrder(request));
    }
}

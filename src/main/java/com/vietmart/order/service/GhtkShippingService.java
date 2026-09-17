package com.vietmart.order.service;

import com.vietmart.order.dto.ShippingOrderRequest;
import com.vietmart.order.dto.ShippingOrderResponse;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class GhtkShippingService {

    private final ConcurrentHashMap<String, AtomicInteger> attemptCounter = new ConcurrentHashMap<>();

    @Retry(name = "ghtkShippingRetry", fallbackMethod = "fallbackCreateShippingOrder")
    public ShippingOrderResponse createShippingOrder(ShippingOrderRequest request) throws TimeoutException {
        AtomicInteger counter = attemptCounter.computeIfAbsent(request.getOrderId(), k -> new AtomicInteger(0));
        int currentAttempt = counter.incrementAndGet();

        log.info("[GHTK CLIENT] Attempt #{} calling GHTK API for Order: {}, IdempotencyKey: {}",
                currentAttempt, request.getOrderId(), request.getIdempotencyKey());

        if (currentAttempt < 3) {
            log.warn("[GHTK CLIENT] Network Glitch / Timeout on attempt #{} for Order {}", currentAttempt, request.getOrderId());
            throw new TimeoutException("GHTK Connection timeout during network glitch");
        }

        log.info("[GHTK CLIENT SUCCESS] Successfully created shipping order on attempt #{}", currentAttempt);
        attemptCounter.remove(request.getOrderId());

        return ShippingOrderResponse.builder()
                .trackingCode("GHTK-" + System.currentTimeMillis())
                .orderId(request.getOrderId())
                .carrier("GIAO_HANG_TIET_KIEM")
                .shippingFee(new BigDecimal("30000.00"))
                .status("CREATED")
                .message("Van don da duoc tao thanh cong sau " + currentAttempt + " lan thu")
                .build();
    }

    public ShippingOrderResponse fallbackCreateShippingOrder(ShippingOrderRequest request, Throwable ex) {
        log.error("[GHTK FALLBACK] All retry attempts failed for Order {}. Reason: {}", request.getOrderId(), ex.getMessage());
        attemptCounter.remove(request.getOrderId());

        return ShippingOrderResponse.builder()
                .trackingCode(null)
                .orderId(request.getOrderId())
                .carrier("GIAO_HANG_TIET_KIEM")
                .shippingFee(BigDecimal.ZERO)
                .status("FAILED_PENDING_RETRY")
                .message("He thong GHTK tam thoi khong the ket noi. Yeu cau da duoc ghi nhan de thu lai sau.")
                .build();
    }
}

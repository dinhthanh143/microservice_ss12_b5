# Báo Cáo Phân Tích & Thiết Kế Kiến Trúc: Trade-Off Giữa Circuit Breaker Và Retry Pattern

## Phần 1 — Đề Xuất Đa Giải Pháp

Đứng trước bài toán hệ thống vận chuyển GHTK thường xuyên bị "chớp nháy" mạng (Network Glitch / Transient Failure) — lâu lâu có 1 request bị Timeout nhưng gọi lại ngay thì thành công, System Architect đề xuất 2 giải pháp:

### 1.1. Giải pháp 1: Dùng Circuit Breaker thuần túy
- **Cơ chế**: Theo dõi tỷ lệ lỗi (failure rate) trong một cửa sổ trượt (sliding window). Nếu tỷ lệ timeout vượt ngưỡng (ví dụ: > 50%), Circuit Breaker sẽ chuyển từ trạng thái `CLOSED` sang `OPEN` và **ngắt toàn bộ các request tiếp theo**, trực tiếp trả về fallback mà không gửi request sang GHTK trong một khoảng thời gian chờ (`waitDurationInOpenState`).
- **Ứng dụng thực tế**: Bảo vệ Order Service khỏi việc cạn kiệt tài nguyên (thread pool exhaustion) khi GHTK bị sập hoàn toàn (Hard Outage / Downtime kéo dài).

### 1.2. Giải pháp 2: Dùng Retry Pattern kết hợp Exponential Backoff & Jitter
- **Cơ chế**: Khi gặp lỗi tạm thời (`TimeoutException`, `SocketTimeoutException`), client không từ bỏ ngay mà tự động thực hiện lại request tối đa $N$ lần (ví dụ: 3 lần), giãn cách giữa các lần thử bằng một khoảng thời gian chờ (Backoff Duration).
- **Ứng dụng thực tế**: Xử lý hoàn hảo các lỗi chớp nháy mạng tức thời, giúp tỷ lệ thành công của việc tạo vận đơn đạt gần 100% mà người dùng cuối không nhận thấy sự cố mạng gián đoạn.

---

## Phần 2 — Bảng So Sánh Chi Tiết: Circuit Breaker vs Retry Pattern

| Tiêu chí so sánh | Giải pháp 1: Circuit Breaker thuần túy | Giải pháp 2: Retry Pattern (Backoff) | Đánh giá kiến trúc tốt nhất |
| :--- | :--- | :--- | :--- |
| **Mục đích cốt lõi** | **Bảo vệ hệ sinh thái**: Ngăn chặn lỗi dây chuyền (cascading failure) khi downstream sập hẳn. | **Tăng tỷ lệ thành công**: Tự phục hồi sau các lỗi mạng chập chờn (Self-healing on transient errors). | **Retry** phù hợp hơn cho lỗi chớp nháy. |
| **Đối phó lỗi chớp nháy (Transient Failure)** | **Kém**: Khi 1 request bị timeout, Circuit Breaker có thể bị kích hoạt nhầm (False Alarm) làm ngắt luôn các đơn hàng khác dù mạng đã bình thường. | **Rất tốt**: Thử lại sau 1-2 giây sẽ thành công ngay, đơn hàng được tạo bình thường mà không cần người dùng thao tác lại. | **Retry** vượt trội hoàn toàn. |
| **Đối phó khi hệ thống GHTK sập hẳn (System Crash)** | **Rất tốt**: Chuyển sang `OPEN` ngay lập tức, ngắt kết nối để không làm treo hàng nghìn thread của Order-Service. | **Nguy hiểm**: Tiếp tục retry liên tục $3 \times \text{Requests}$, tạo nên cơn bão request (Retry Storm) bóp nghẹt thêm hệ thống GHTK. | **Circuit Breaker** vượt trội khi sập hẳn. |
| **Thời gian phản hồi người dùng (Latency)** | Nhanh khi `OPEN` (fail-fast), nhưng đơn hàng bị báo lỗi ngay lập tức. | Chậm hơn đôi chút ở request bị lỗi do phải chờ retry ($2\text{s} + 2\text{s}$), nhưng kết quả cuối cùng là **Thành công**. | **Retry** tối ưu trải nghiệm khách hàng. |
| **Rủi ro trùng lặp giao dịch (Side Effects)** | Thấp (do chỉ gọi 1 lần rồi ngắt). | **Rất cao** nếu API không có tính chất lũy đẳng (Idempotency). | Cần thiết kế **Idempotency Key**. |

---

## Phần 3 — Triển Khai Giải Pháp Tốt Nhất

### 3.1. Chốt giải pháp
- Đối với đặc thù **lỗi chớp nháy mạng (Transient Failure)**, **Giải pháp 2 (Retry Pattern)** là giải pháp tối ưu trực tiếp nhất để tăng tỷ lệ tạo đơn thành công.
- *(Best Practice trong môi trường Production là kết hợp cả hai: Bọc Retry bên trong Circuit Breaker: Retry xử lý transient glitch, nếu retry hết lần mà vẫn fail liên tục thì Circuit Breaker mở ra để bảo vệ toàn hệ thống).*

### 3.2. Cấu hình YAML cho Resilience4j Retry (`application.yml`)
Cấu hình tự động thử lại tối đa 3 lần, mỗi lần cách nhau 2 giây khi gặp `TimeoutException`:

```yaml
server:
  port: 8080

spring:
  application:
    name: order-shipping-service

# Cấu hình Resilience4j Retry
resilience4j:
  retry:
    instances:
      ghtkShippingRetry:
        max-attempts: 3                                    # Tối đa 3 lần thử (1 lần gọi gốc + 2 lần retry)
        wait-duration: 2s                                   # Mỗi lần thử cách nhau 2 giây
        enable-exponential-backoff: false
        retry-exceptions:                                   # Chỉ retry khi gặp lỗi mạng/timeout tạm thời
          - java.util.concurrent.TimeoutException
          - org.springframework.web.client.ResourceAccessException
          - java.net.SocketTimeoutException
        ignore-exceptions:                                  # Bỏ qua lỗi nghiệp vụ (không retry vô ích)
          - java.lang.IllegalArgumentException
```

---

### 3.3. Mã nguồn Java Demo Triển Khai (`GhtkShippingService.java`)
```java
package com.vietmart.order.service;

import com.vietmart.order.dto.ShippingOrderRequest;
import com.vietmart.order.dto.ShippingOrderResponse;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class GhtkShippingService {

    private final AtomicInteger attemptCounter = new AtomicInteger(0);

    @Retry(name = "ghtkShippingRetry", fallbackMethod = "fallbackCreateShippingOrder")
    public ShippingOrderResponse createShippingOrder(ShippingOrderRequest request) throws TimeoutException {
        int currentAttempt = attemptCounter.incrementAndGet();
        log.info("[GHTK CLIENT] Attempt #{} calling GHTK API for Order: {}, IdempotencyKey: {}",
                currentAttempt, request.getOrderId(), request.getIdempotencyKey());

        // Giả lập 2 lần đầu bị Timeout do chớp nháy mạng, lần 3 thành công
        if (currentAttempt < 3) {
            log.warn("[GHTK CLIENT] Network Glitch / Timeout on attempt #{}", currentAttempt);
            throw new TimeoutException("GHTK Connection timeout during network glitch");
        }

        log.info("[GHTK CLIENT SUCCESS] Successfully created shipping order on attempt #{}", currentAttempt);
        return ShippingOrderResponse.builder()
                .trackingCode("GHTK-VN-" + System.currentTimeMillis())
                .orderId(request.getOrderId())
                .carrier("GIAO_HANG_TIET_KIEM")
                .shippingFee(new BigDecimal("30000.00"))
                .status("CREATED")
                .message("Vận đơn tạo thành công sau " + currentAttempt + " lần thử")
                .build();
    }

    public ShippingOrderResponse fallbackCreateShippingOrder(ShippingOrderRequest request, Throwable ex) {
        log.error("[GHTK FALLBACK] All retry attempts failed for Order {}. Reason: {}", request.getOrderId(), ex.getMessage());
        return ShippingOrderResponse.builder()
                .trackingCode(null)
                .orderId(request.getOrderId())
                .carrier("GIAO_HANG_TIET_KIEM")
                .shippingFee(BigDecimal.ZERO)
                .status("FAILED_PENDING_RETRY")
                .message("Hệ thống GHTK tạm thời không phản hồi. Yêu cầu đã được chuyển vào hàng đợi xử lý sau.")
                .build();
    }
}
```

---

## Phần 4 — Bẫy Dữ Liệu: Rủi Ro Khi GHTK Trừ Tiền Tài Khoản & Khái Niệm Idempotency

### 4.1. Bẫy dữ liệu (The Double-Spending / Duplicate Billing Trap)
- **Kịch bản rủi ro**:
  1. Order Service gửi request tạo vận đơn lần 1 sang GHTK.
  2. GHTK đã **nhận được request, trừ 30.000đ trong tài khoản và tạo mã vận đơn thành công**.
  3. Tuy nhiên, trên đường truyền trả response từ GHTK về Order Service, mạng bị đứt (Network Glitch) $\rightarrow$ Order Service bị `TimeoutException`.
  4. Cơ chế Retry tự động kích hoạt lần 2 và lần 3 $\rightarrow$ GHTK nhận tiếp 2 request mới và **tiếp tục trừ tiền thêm 2 lần nữa** (bị trừ tổng cộng 90.000đ cho cùng 1 đơn hàng) và tạo ra 3 mã vận đơn trùng lặp.

---

### 4.2. Khái niệm Idempotency (Tính Lũy Đẳng)
- **Định nghĩa**: Một thao tác/API được gọi là **Lũy đẳng (Idempotent)** nếu việc thực thi thao tác đó $N$ lần liên tiếp ($N \ge 1$) với cùng một tập tham số đầu vào đều cho ra **kết quả giống hệt như thực thi 1 lần duy nhất**, và không làm thay đổi thêm trạng thái của hệ thống máy chủ (No additional side effects).
- Trong REST: `GET`, `PUT`, `DELETE` về bản chất là Idempotent; còn `POST` mặc định là **Non-Idempotent**.

### 4.3. Giải pháp khắc phục triệt để khi Retry API trừ tiền
1. **Sử dụng Idempotency Key (Khóa lũy đẳng)**:
   - Client sinh một `Idempotency-Key` duy nhất cho mỗi đơn hàng (ví dụ UUID hoặc `orderId`) và truyền trong Header/Body sang GHTK:
     ```http
     POST /api/v1/shipping/orders
     X-Idempotency-Key: ORD-2026-X89-UUID
     ```
2. **Xử lý tại phía GHTK Server**:
   - GHTK lưu `Idempotency-Key` vào Redis/Database kèm trạng thái giao dịch.
   - Khi nhận request retry có `Idempotency-Key` đã tồn tại: GHTK **không trừ tiền thêm lần nào nữa**, không tạo đơn mới mà chỉ trả về kết quả vận đơn đã tạo từ lần 1.

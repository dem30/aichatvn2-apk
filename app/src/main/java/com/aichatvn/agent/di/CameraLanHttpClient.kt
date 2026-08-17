package com.aichatvn.agent.di

import javax.inject.Qualifier

/**
 * CameraLanHttpClient
 *
 * ⚠️ MỚI (thiết kế lại theo đúng chuẩn, không vá riêng theo 1 loại camera): trước đây MỌI class
 * gọi HTTP/SOAP tới camera trên LAN (OnvifEventRelay, CameraCapabilityProber x2, CameraAutoDiscoveryTool,
 * SnapshotFetcher) đều tự `OkHttpClient.Builder().build()` riêng — mỗi client 1 ConnectionPool
 * mặc định (5 idle connections, giữ 5 phút) độc lập nhau.
 *
 * Vấn đề: app kết nối tới hàng trăm loại camera OEM khác nhau, rất nhiều loại có HTTP server nhúng
 * (firmware giá rẻ) không tuân thủ keep-alive đúng chuẩn — tự đóng socket ngay sau khi trả lời 1
 * request, nhưng OkHttp (nếu dùng pool mặc định) vẫn lấy connection đó ra tái sử dụng cho request
 * kế tiếp tới CÙNG camera → ghi lên socket đã chết → "unexpected end of stream" (hoặc các dạng
 * IOException transport khác tuỳ firmware: EOFException, SocketException reset/broken pipe...).
 * Đây là đặc tính chung của cả 1 LỚP thiết bị (camera LAN giá rẻ), không phải lỗi riêng 1 con
 * camera cụ thể nào — nên fix phải nằm ở 1 nơi DUY NHẤT áp dụng cho MỌI nơi gọi HTTP tới camera,
 * không phải vá lẻ từng file mỗi khi gặp 1 con camera mới bị.
 *
 * Qualifier này đánh dấu 1 OkHttpClient DÙNG CHUNG (cấu hình ConnectionPool ở NetworkModule/
 * AppModule) cho MỌI lời gọi HTTP/SOAP tới thiết bị camera trên LAN — khác hẳn OkHttpClient gọi
 * lên Gateway (server thật trên Render, không phải firmware camera, không cùng lớp lỗi này) mà
 * DeviceCommandGatewayClient/GatewaySignalingManager/HouseholdEventPublisher vẫn tự quản lý riêng.
 *
 * Nơi cần timeout khác nhau (probe cần ngắn để quét nhanh, PullMessages ONVIF cần dài để long-poll)
 * KHÔNG tạo OkHttpClient mới từ đầu — dùng `sharedClient.newBuilder().readTimeout(...).build()`,
 * theo đúng khuyến nghị chính thức của OkHttp: derive client con qua newBuilder() vẫn CHIA SẺ
 * ConnectionPool + Dispatcher với client gốc, chỉ khác timeout/interceptor.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CameraLanHttpClient
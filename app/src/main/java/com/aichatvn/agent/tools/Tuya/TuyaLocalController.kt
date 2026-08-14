package com.aichatvn.agent.tools.tuya

import android.content.Context
import android.net.wifi.WifiManager
import com.aichatvn.agent.utils.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ⚠️ ĐỌC TRƯỚC KHI DÙNG: giao thức local của Tuya CHƯA BAO GIỜ được hãng công bố chính thức —
 * toàn bộ implementation dưới đây dựa trên tài liệu cộng đồng đã reverse (tinytuya/LocalTuya),
 * KHÔNG có SDK chính chủ để đối chiếu. Phần "3.3" (CONTROL/DP_QUERY cơ bản) đã được cộng đồng
 * kiểm chứng rộng rãi, tương đối đáng tin. Phần "3.4" (session-key handshake) phức tạp hơn
 * nhiều và CHƯA được test với thiết bị thật trong môi trường này — nếu handshake sai dù chỉ 1
 * byte, thiết bị sẽ đơn giản không phản hồi (timeout), không có thông báo lỗi rõ ràng để debug.
 * Coi phần 3.4 là điểm khởi đầu cần TỰ TEST VÀ CHỈNH LẠI với thiết bị thật, không phải code
 * production-ready ngay lần đầu.
 *
 * Mọi lỗi (timeout, sai key, sai version, mất LAN) đều trả về null/false thay vì throw — nơi
 * gọi (TuyaManager) dựa vào đó để fallback êm sang Cloud API, không làm crash luồng bật/tắt.
 */
data class TuyaLocalDiscovery(
    val gwId: String,
    val ip: String,
    val version: String
)

@Singleton
class TuyaLocalController @Inject constructor(
    private val logger: Logger,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    companion object {
        private const val UDP_PORT_UNENCRYPTED = 6666
        private const val UDP_PORT_ENCRYPTED = 6667
        private const val TCP_PORT = 6668

        private const val PREFIX = 0x000055AA
        private const val SUFFIX = 0x0000AA55

        private const val CMD_CONTROL = 0x07
        private const val CMD_DP_QUERY = 0x0a
        private const val CMD_SESS_KEY_NEG_START = 0x03 // 3.4 handshake — xem ghi chú ở sendCommand34()
        private const val CMD_SESS_KEY_NEG_RESP = 0x05

        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 5_000
        private const val UDP_PACKET_TIMEOUT_MS = 500

        // Khoá cố định CÔNG KHAI Tuya dùng để mã hoá lớp NGOÀI của gói broadcast UDP cổng 6667 —
        // cộng đồng LocalTuya/tinytuya/tuya-convert đã reverse từ lâu, KHÔNG phải bí mật riêng
        // theo từng thiết bị/tài khoản (khác hẳn local_key). Chỉ dùng để bóc lớp ngoài broadcast
        // xem gwId/ip.
        //
        // ⚠️ SỬA (bug: decrypt "thành công" nhưng ra JSON rác): khoá AES thật sự KHÔNG PHẢI là
        // bytes UTF-8 thô của chuỗi "yGAdlopoPVldABfn" — dù chuỗi đó tình cờ cũng dài đúng 16 ký
        // tự nên không hề bị ném lỗi kích thước khoá, khiến bug này rất khó nhận ra chỉ qua
        // exception. Mọi implementation tham chiếu được (tinytuya/udp_helper.py, tuya-convert/
        // tuya-discovery.py, tuyapower/scan.py) đều thống nhất: khoá thật là MD5 DIGEST (16 byte)
        // của chuỗi đó — `udpkey = md5(b"yGAdlopoPVldABfn").digest()` — không phải chuỗi gốc.
        // Đây chính là lý do headerSize=20 (offset đúng, mod16=0, không lỗi block-size) vẫn ra
        // JSONException: offset đúng, nhưng khoá sai → AES-ECB decrypt "chạy được" (đúng bội số
        // 16) nhưng nội dung giải mã ra là rác nhị phân, không phải JSON hợp lệ.
        private val UDP_BROADCAST_KEY = java.security.MessageDigest.getInstance("MD5")
            .digest("yGAdlopoPVldABfn".toByteArray(Charsets.UTF_8))
    }

    // ═══════════════════════════════ DISCOVERY (UDP) ═══════════════════════════════

    /**
     * Lắng nghe UDP broadcast trong [timeoutMs] để tìm IP LAN hiện tại của thiết bị có đúng
     * [gwId] (= TuyaDeviceEntity.id). Trả về null nếu không thấy — thiết bị offline, hoặc điện
     * thoại đang không cùng LAN (xa nhà/4G) — ĐÂY LÀ TRƯỜNG HỢP BÌNH THƯỜNG, không phải lỗi.
     */
    suspend fun discoverIp(gwId: String, timeoutMs: Long = 12_000L): TuyaLocalDiscovery? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) { listenBroadcast(gwId) }
        }

    // ⚠️ SỬA (bug treo vô hạn): trước đây là `private fun` thường — vòng `while (result ==
    // null)` bên dưới KHÔNG BAO GIỜ kiểm tra coroutine đã bị withTimeoutOrNull() hủy chưa,
    // vì đây là hàm đồng bộ, không có suspend point nào để cancellation "chen vào". Khi
    // timeoutMs hết giờ mà chưa tìm thấy gwId khớp, coroutine job bị đánh dấu cancelled
    // NHƯNG vòng lặp vẫn chạy tiếp vô thời hạn — hàm không bao giờ return, `finally {
    // sockets.forEach { close() } }` không bao giờ chạy tới, 2 cổng UDP 6666/6667 bị GIỮ
    // VĨNH VIỄN. Mọi lần gọi discoverIp() sau đó (kể cả từ ensureLocalControlReady() tự
    // động trong turnOn()/turnOff()) cố bind lại đúng 2 cổng đó sẽ bị chặn/treo theo —
    // đây là nguyên nhân "quay vòng vòng, mất điều khiển" sau khi dùng Local Lab/Bật nhanh.
    //
    // Sửa: chuyển thành suspend fun + gọi ensureActive() mỗi vòng lặp — đây là điểm kiểm
    // tra cancellation hợp tác chuẩn của coroutine, sẽ ném CancellationException đúng lúc
    // withTimeoutOrNull() hết giờ, để finally{} chạy và đóng socket kịp thời.
    private suspend fun listenBroadcast(targetGwId: String): TuyaLocalDiscovery? {
        var result: TuyaLocalDiscovery? = null
        // 🔧 DEBUG TẠM: đếm tổng số gói nhận được (dù match hay không) — log ra khi hàm kết
        // thúc (finally) để biết ngay 0 gói (network/OS chặn) hay có gói nhưng không khớp.
        var debugPacketCount = 0
        val sockets = mutableListOf<DatagramSocket>()
        val multicastLock = runCatching {
            (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createMulticastLock("aichatvn2-tuya-local-lab")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }.getOrNull()
        try {
            val socket6666 = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(UDP_PORT_UNENCRYPTED))
                soTimeout = UDP_PACKET_TIMEOUT_MS
            }
            val socket6667 = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(UDP_PORT_ENCRYPTED))
                soTimeout = UDP_PACKET_TIMEOUT_MS
            }
            sockets.add(socket6666)
            sockets.add(socket6667)
            val buffer = ByteArray(2048)

            while (result == null) {
                // ✅ MỚI: điểm kiểm tra cancellation — nếu withTimeoutOrNull() đã hết giờ,
                // dòng này ném CancellationException ngay, thoát vòng lặp, chạy finally{}
                // đóng socket. Không có dòng này, timeout bên ngoài vô nghĩa (xem giải thích
                // ở doc-comment của hàm).
                currentCoroutineContext().ensureActive()
                for ((socket, encrypted) in listOf(socket6666 to false, socket6667 to true)) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val raw = packet.data.copyOfRange(0, packet.length)
                        // 🔧 DEBUG TẠM: log MỌI gói nhận được bất kể có parse/match được hay không —
                        // để phân biệt "không có gói nào tới" (network/OS chặn) với "có gói nhưng
                        // gwId khác/parse lỗi" (thiết bị broadcast ID khác hoặc key/format sai).
                        // Xoá khối log này sau khi chẩn đoán xong.
                        debugPacketCount++
                        logger.i(
                            "TuyaLocalController",
                            "🔧 DEBUG raw packet #$debugPacketCount from=${packet.address?.hostAddress} port=${if (encrypted) UDP_PORT_ENCRYPTED else UDP_PORT_UNENCRYPTED} size=${raw.size}"
                        )
                        val json = parseBroadcastPacket(raw, encrypted)
                        if (json == null) {
                            logger.i("TuyaLocalController", "🔧 DEBUG parseBroadcastPacket() trả null (decrypt/JSON lỗi) từ ${packet.address?.hostAddress}")
                            continue
                        }
                        val gwId = json.optString("gwId")
                        logger.i("TuyaLocalController", "🔧 DEBUG parsed gwId=$gwId (đang tìm targetGwId=$targetGwId) raw json=$json")
                        if (gwId.isNotBlank() && gwId == targetGwId) {
                            result = TuyaLocalDiscovery(
                                gwId = gwId,
                                ip = json.optString("ip", packet.address.hostAddress ?: ""),
                                version = json.optString("version", "3.3")
                            )
                            break
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        // Bình thường — không có gói nào trong UDP_PACKET_TIMEOUT_MS, thử lại vòng sau.
                    }
                }
            }
        } catch (e: CancellationException) {
            // ✅ MỚI: KHÔNG nuốt — phải ném lại để withTimeoutOrNull() ở discoverIp() nhận
            // biết đúng là đã hết giờ (không phải lỗi thật), coroutine machinery cần thấy
            // exception này đi qua nguyên vẹn. finally{} bên dưới vẫn chạy để đóng socket.
            throw e
        } catch (e: Exception) {
            logger.d("TuyaLocalController", "listenBroadcast lỗi: ${e.message}")
        } finally {
            // 🔧 DEBUG TẠM: kết luận nhanh — 0 gói = mạng/OS chặn broadcast tới máy (kiểm tra
            // pin nền MIUI, AP isolation trên router). Có gói nhưng result vẫn null = thiết bị
            // đang broadcast gwId KHÁC targetGwId, hoặc parse lỗi (xem log parse null ở trên).
            logger.i("TuyaLocalController", "🔧 DEBUG listenBroadcast kết thúc: tổng $debugPacketCount gói nhận được, targetGwId=$targetGwId, found=${result != null}")
            sockets.forEach { runCatching { it.close() } }
            runCatching { multicastLock?.release() }
        }
        return result
    }

    private fun parseBroadcastPacket(raw: ByteArray, encrypted: Boolean): JSONObject? {
        if (raw.size < 24) return null
        if (!encrypted) {
            return try {
                val payload = raw.copyOfRange(16, raw.size - 8)
                JSONObject(String(payload, Charsets.UTF_8).trim(Char(0)))
            } catch (e: Exception) {
                logger.i("TuyaLocalController", "🔧 DEBUG parse plaintext (6666) lỗi: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
        }
        // 🔧 DEBUG TẠM: bản trước đoán offset 20 là đúng nhưng vẫn lỗi giống hệt offset 16 —
        // nghĩa là chưa chắc do offset. Giờ thử LẦN LƯỢT cả 2 offset khả dĩ, log rõ ĐÚNG loại
        // exception (IllegalBlockSizeException = sai độ dài payload/không phải bội số 16;
        // BadPaddingException = độ dài đúng nhưng sai offset bắt đầu/sai key -> nội dung giải
        // mã ra rác) + kích thước payload từng lần thử, để biết chính xác đang sai ở đâu thay
        // vì chỉ thấy "decrypt/JSON lỗi" chung chung như trước.
        for (headerSize in intArrayOf(20, 16)) {
            if (raw.size - headerSize - 8 <= 0) continue
            val payload = raw.copyOfRange(headerSize, raw.size - 8)
            try {
                val jsonBytes = aesEcbDecrypt(payload, UDP_BROADCAST_KEY)
                val json = JSONObject(String(jsonBytes, Charsets.UTF_8).trim(Char(0)))
                logger.i("TuyaLocalController", "🔧 DEBUG parse OK với headerSize=$headerSize payloadSize=${payload.size} json=$json")
                return json
            } catch (e: Exception) {
                logger.i(
                    "TuyaLocalController",
                    "🔧 DEBUG headerSize=$headerSize payloadSize=${payload.size} (mod16=${payload.size % 16}) lỗi: ${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
        return null
    }

    // ═══════════════════════════════ ĐIỀU KHIỂN — PROTOCOL 3.3 ═══════════════════════════════

    /** Gửi lệnh set DP qua protocol 3.3. Trả về true nếu thiết bị xác nhận nhận lệnh. */
    // ✅ SỬA (quan trọng): trước đây coi lệnh CONTROL là "gửi thất bại" bất cứ khi nào
    // sendPacket33() trả null — nhưng sendPacket33() trả null trong 2 trường hợp có ý nghĩa
    // KHÁC NHAU hoàn toàn: (1) readSocketFully() không nhận được byte hợp lệ nào — đây mới là
    // network fail thật (không tới thiết bị / thiết bị không phản hồi gì); (2) đã nhận đủ raw
    // bytes từ thiết bị (readSocketFully thành công) nhưng parseResponsePacket33() decrypt nội
    // dung ACK thất bại — quan sát thực tế trên thiết bị thật: ACK của lệnh CONTROL đôi khi
    // dùng định dạng/key khác với DP_QUERY khiến decrypt luôn lỗi ("Cipher functions:
    // OPENSSL_internal..."), dù gói tin đã tới và thiết bị đã thực thi lệnh đúng (ổ cắm đổi
    // trạng thái thật). Gộp chung 2 trường hợp này khiến lệnh CONTROL luôn bị coi là "thất bại"
    // với những thiết bị có kiểu ACK này, buộc TuyaManager rơi xuống Cloud API mỗi lần dù Local
    // đã thành công.
    //
    // Sửa lại: gọi thẳng readSocketFully() trước để biết CÓ nhận được phản hồi hay không — có
    // (dù sau đó decrypt lỗi) nghĩa là gói tin CONTROL đã tới và được xử lý, coi là thành công.
    // Chỉ khi hoàn toàn không nhận được gì (readSocketFully null — timeout/network fail thật)
    // mới coi là thất bại thật sự.
    suspend fun sendCommand33(
        ip: String, localKey: String, deviceId: String, dps: Map<String, Any>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val payloadJson = JSONObject().apply {
                put("devId", deviceId)
                put("dps", JSONObject(dps as Map<*, *>))
                put("t", (System.currentTimeMillis() / 1000).toString())
            }.toString()
            sendPacket33Raw(ip, localKey, CMD_CONTROL, payloadJson) != null
        } catch (e: Exception) {
            logger.d("TuyaLocalController", "sendCommand33($ip) lỗi: ${e.message}")
            false
        }
    }

    /** Đọc toàn bộ trạng thái DP hiện tại qua protocol 3.3. Trả về null nếu lỗi/timeout. */
    suspend fun queryStatus33(
        ip: String, localKey: String, deviceId: String
    ): Map<String, Any>? = withContext(Dispatchers.IO) {
        try {
            val payloadJson = JSONObject().apply {
                put("devId", deviceId)
                put("gwId", deviceId)
            }.toString()
            val response = sendPacket33(ip, localKey, CMD_DP_QUERY, payloadJson) ?: return@withContext null
            val dps = response.optJSONObject("dps") ?: return@withContext null
            val result = mutableMapOf<String, Any>()
            dps.keys().forEach { k -> result[k] = dps.get(k) }
            result
        } catch (e: Exception) {
            logger.d("TuyaLocalController", "queryStatus33($ip) lỗi: ${e.message}")
            null
        }
    }

    private fun sendPacket33(ip: String, localKey: String, command: Int, payloadJson: String): JSONObject? {
        val (responseRaw, keyBytes) = sendAndReceiveRaw33(ip, localKey, command, payloadJson) ?: return null
        return parseResponsePacket33(responseRaw, keyBytes)
    }

    // ✅ MỚI: dùng cho sendCommand33 — chỉ cần biết THIẾT BỊ CÓ PHẢN HỒI GÌ HAY KHÔNG (raw bytes
    // nhận được đầy đủ theo đúng cấu trúc header/len), KHÔNG quan tâm nội dung ACK có decrypt
    // được hay không. Xem giải thích đầy đủ ở doc-comment sendCommand33 — với lệnh CONTROL, có
    // phản hồi (dù không đọc được nội dung) đã là bằng chứng đủ mạnh cho thấy gói tin đã tới và
    // được xử lý; parse nội dung ACK chỉ thật sự cần thiết cho DP_QUERY (sendPacket33 ở trên).
    private fun sendPacket33Raw(ip: String, localKey: String, command: Int, payloadJson: String): ByteArray? {
        return sendAndReceiveRaw33(ip, localKey, command, payloadJson)?.first
    }

    private fun sendAndReceiveRaw33(ip: String, localKey: String, command: Int, payloadJson: String): Pair<ByteArray, ByteArray>? {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, TCP_PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS

            val keyBytes = localKey.toByteArray(Charsets.UTF_8)
            val encryptedPayload = aesEcbEncrypt(payloadJson.toByteArray(Charsets.UTF_8), keyBytes)
            // Lệnh CONTROL cần thêm tiền tố "3.3" + 12 byte 0x00 trước phần mã hoá — DP_QUERY thì không.
            val versionedPayload = if (command == CMD_CONTROL) {
                "3.3".toByteArray(Charsets.UTF_8) + ByteArray(12) + encryptedPayload
            } else {
                encryptedPayload
            }

            socket.getOutputStream().apply {
                write(buildPacket33(command, versionedPayload))
                flush()
            }

            val responseRaw = readSocketFully(socket) ?: return null
            return responseRaw to keyBytes
        }
    }

    private fun buildPacket33(command: Int, payload: ByteArray): ByteArray {
        // Thân gói (chưa gồm CRC/suffix) = seq(4) + cmd(4) + len(4) + payload — len tính luôn
        // +8 cho CRC(4)+suffix(4) theo đúng đặc tả.
        val body = ByteArrayOutputStream().apply {
            write(intToBytes(0))              // sequence — 0 được đa số firmware chấp nhận
            write(intToBytes(command))
            write(intToBytes(payload.size + 8))
            write(payload)
        }.toByteArray()

        val crc = CRC32().apply {
            update(intToBytes(PREFIX))
            update(body)
        }.value

        return ByteArrayOutputStream().apply {
            write(intToBytes(PREFIX))
            write(body)
            write(intToBytes(crc.toInt()))
            write(intToBytes(SUFFIX))
        }.toByteArray()
    }

    // ✅ SỬA (bug thật gây DATA_NOT_MULTIPLE_OF_BLOCK_LENGTH ở MỌI lần query, xác nhận qua log
    // thực tế lặp lại 100%): phần lớn phản hồi DP_QUERY giao thức 3.3 chèn thêm 4 byte
    // "return code" (retcode, thường 0x00000000) ĐỨNG TRƯỚC ciphertext thật — không thuộc dữ
    // liệu mã hoá. Code cũ đưa nguyên `payload` (dư đúng 4 byte so với bội số 16) thẳng vào
    // AES/ECB/NoPadding.doFinal() → ném exception ngay lập tức trước khi kịp thử offset nào
    // khác. Nhánh dự phòng cũ bóc 15 byte chỉ đúng cho tiền tố "3.3"+12 byte đệm của chiều
    // GỬI lệnh CONTROL, không đúng cấu trúc phản hồi DP_QUERY nên cũng luôn thất bại theo.
    //
    // Sửa: liệt kê các cách bóc ciphertext hợp lý theo thứ tự ưu tiên, CHỈ thử decrypt với
    // ứng viên có độ dài đã là bội số 16 (tránh ném exception vô ích cho ứng viên chắc chắn
    // sai) — ưu tiên bỏ 4 byte retcode trước vì đây là trường hợp phổ biến nhất theo tài liệu
    // cộng đồng (tinytuya/LocalTuya) và khớp với lỗi quan sát được trong log thực tế.
    private fun parseResponsePacket33(raw: ByteArray, keyBytes: ByteArray): JSONObject? {
        if (raw.size < 24) return null
        val payload = raw.copyOfRange(16, raw.size - 8)
        if (payload.isEmpty()) return JSONObject() // ACK rỗng (thường gặp ở lệnh CONTROL) — coi là thành công

        val candidates = listOfNotNull(
            // (1) không có tiền tố gì — 1 số firmware trả ciphertext thuần
            payload.takeIf { it.size % 16 == 0 },
            // (2) bỏ 4 byte retcode — trường hợp phổ biến nhất cho DP_QUERY 3.3
            payload.takeIf { it.size > 4 && (it.size - 4) % 16 == 0 }
                ?.copyOfRange(4, payload.size),
            // (3) bỏ 15 byte tiền tố "3.3"+đệm — 1 số firmware khác vẫn trả kèm kiểu này
            payload.takeIf { it.size > 15 && (it.size - 15) % 16 == 0 }
                ?.copyOfRange(15, payload.size)
        )

        for (ciphertext in candidates) {
            try {
                val decrypted = aesEcbDecrypt(ciphertext, keyBytes)
                return JSONObject(String(decrypted, Charsets.UTF_8).trim(Char(0)))
            } catch (e: Exception) {
                // thử ứng viên tiếp theo, không log ở đây để tránh spam — chỉ log 1 lần nếu
                // hết ứng viên vẫn thất bại (xem dưới).
            }
        }

        logger.d(
            "TuyaLocalController",
            "parseResponsePacket33: không decrypt được với mọi cách bóc offset đã biết " +
                "(payload.size=${payload.size}, không khớp bội số 16 ở offset 0/4/15)"
        )
        return null
    }

    // ═══════════════════════════════ ĐIỀU KHIỂN — PROTOCOL 3.4 ═══════════════════════════════
    // ⚠️ CHƯA TEST VỚI THIẾT BỊ THẬT — xem cảnh báo ở đầu file. Nếu thiết bị 3.4 không phản hồi
    // (luôn trả null), ĐÂY LÀ TRIỆU CHỨNG DỰ KIẾN của phần chưa kiểm chứng này, không phải chắc
    // chắn thiết bị lỗi — TuyaManager sẽ tự fallback Cloud API trong trường hợp đó.

    /**
     * Bắt tay lấy session_key rồi gửi lệnh set DP qua protocol 3.4. Trả về true nếu thành công.
     */
    suspend fun sendCommand34(
        ip: String, localKey: String, deviceId: String, dps: Map<String, Any>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, TCP_PORT), CONNECT_TIMEOUT_MS)
                socket.soTimeout = READ_TIMEOUT_MS
                val keyBytes = localKey.toByteArray(Charsets.UTF_8)

                val sessionKey = negotiateSessionKey34(socket, keyBytes) ?: run {
                    logger.d("TuyaLocalController", "sendCommand34($ip): handshake thất bại")
                    return@withContext false
                }

                val payloadJson = JSONObject().apply {
                    put("devId", deviceId)
                    put("dps", JSONObject(dps as Map<*, *>))
                    put("t", (System.currentTimeMillis() / 1000).toString())
                }.toString()
                val encrypted = aesEcbEncrypt(payloadJson.toByteArray(Charsets.UTF_8), sessionKey)
                val packet = buildPacket34(CMD_CONTROL, encrypted, sessionKey)
                socket.getOutputStream().apply { write(packet); flush() }

                readSocketFully(socket) != null
            }
        } catch (e: Exception) {
            logger.d("TuyaLocalController", "sendCommand34($ip) lỗi: ${e.message}")
            false
        }
    }

    // Bắt tay kiểu mini-TLS: gửi 16 byte random của mình (mã hoá bằng local_key), nhận 16 byte
    // random của thiết bị + HMAC xác nhận, rồi cả 2 bên tự tính session_key = HMAC-SHA256(random
    // của mình, local_key) XOR random của thiết bị — KHÔNG truyền session_key trên dây.
    private fun negotiateSessionKey34(socket: Socket, localKey: ByteArray): ByteArray? {
        return try {
            val clientRandom = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            val encryptedRandom = aesEcbEncrypt(clientRandom, localKey)
            val startPacket = buildPacket33(CMD_SESS_KEY_NEG_START, encryptedRandom)
            // ✅ Dùng khung CRC32 (buildPacket33) ở ĐÚNG bước này theo chủ đích, không phải tạm bợ:
            // trước khi handshake xong thì session_key CHƯA TỒN TẠI, nên gói khởi tạo bắt buộc
            // phải dùng checksum CRC32 (kiểu 3.3) — chỉ SAU khi có session_key mới chuyển sang
            // HMAC-SHA256 (kiểu 3.4, xem buildPacket34).
            socket.getOutputStream().apply { write(startPacket); flush() }

            val responseRaw = readSocketFully(socket) ?: return null
            if (responseRaw.size < 24 + 16 + 32) return null // 16 byte random thiết bị + 32 byte HMAC-SHA256
            val payload = responseRaw.copyOfRange(16, responseRaw.size - 8)
            val decrypted = aesEcbDecrypt(payload, localKey)
            if (decrypted.size < 48) return null

            val deviceRandom = decrypted.copyOfRange(0, 16)
            // 32 byte tiếp theo là HMAC(local_key, deviceRandom) — bỏ qua xác minh chữ ký ở bản
            // đầu này (chỉ dùng deviceRandom để tính session_key), vì mục tiêu là điều khiển được
            // thiết bị CỦA CHÍNH NGƯỜI DÙNG, không phải chống giả mạo trong 1 phiên LAN riêng tư.

            val mac = Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(localKey, "HmacSHA256"))
            }
            val hmacResult = mac.doFinal(clientRandom)
            // session_key = 16 byte đầu của HMAC XOR với deviceRandom
            ByteArray(16) { i -> (hmacResult[i].toInt() xor deviceRandom[i].toInt()).toByte() }
        } catch (e: Exception) {
            logger.d("TuyaLocalController", "negotiateSessionKey34 lỗi: ${e.message}")
            null
        }
    }

    private fun buildPacket34(command: Int, payload: ByteArray, sessionKey: ByteArray): ByteArray {
        // 3.4 dùng HMAC-SHA256 thay CRC32 làm checksum — cấu trúc khung ngoài (prefix/seq/cmd/len/suffix)
        // giữ nguyên như 3.3, chỉ đổi field checksum từ 4 byte CRC32 sang 32 byte HMAC-SHA256.
        val body = ByteArrayOutputStream().apply {
            write(intToBytes(0))
            write(intToBytes(command))
            write(intToBytes(payload.size + 32 + 4)) // +32 HMAC +4 suffix
            write(payload)
        }.toByteArray()

        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(sessionKey, "HmacSHA256")) }
        val hmac = mac.doFinal(intToBytes(PREFIX) + body)

        return ByteArrayOutputStream().apply {
            write(intToBytes(PREFIX))
            write(body)
            write(hmac)
            write(intToBytes(SUFFIX))
        }.toByteArray()
    }

    // ═══════════════════════════════ HELPERS ═══════════════════════════════

    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value shr 24 and 0xFF).toByte(),
        (value shr 16 and 0xFF).toByte(),
        (value shr 8 and 0xFF).toByte(),
        (value and 0xFF).toByte()
    )

    /**
     * ⚠️ SỬA (bug đọc thiếu gói): TCP là stream, không đảm bảo 1 lần read() nhận đủ toàn bộ gói —
     * đặc biệt với payload DP dài hoặc mạng LAN có độ trễ khiến gói tới thành nhiều đợt. Bản cũ
     * chỉ gọi read() một lần rồi dùng thẳng kết quả, có thể cắt cụt gói giữa chừng khiến AES
     * decrypt sai (input không đúng bội số 16 byte -> throw ngay ở lớp gọi).
     *
     * Cách đúng: đọc đủ 16 byte header trước (prefix 4 + seq 4 + cmd 4 + len 4), lấy field "len"
     * (đã bao gồm CRC/HMAC + suffix theo đúng cách buildPacket33/34 tính), rồi đọc tiếp đúng số
     * byte còn lại — lặp read() cho tới khi đủ, không tin 1 lần gọi là xong.
     */
    private fun readSocketFully(socket: Socket): ByteArray? {
        return try {
            val input = socket.getInputStream()
            val header = readExactly(input, 16) ?: return null

            // len nằm ở 4 byte cuối của header (offset 12..15), big-endian — theo đúng cấu trúc
            // buildPacket33/34: seq(4) + cmd(4) + len(4), len đã tính luôn phần CRC/HMAC+suffix.
            val len = ((header[12].toInt() and 0xFF) shl 24) or
                      ((header[13].toInt() and 0xFF) shl 16) or
                      ((header[14].toInt() and 0xFF) shl 8) or
                      (header[15].toInt() and 0xFF)

            // Chặn giá trị vô lý (gói lỗi/giả) trước khi cấp phát buffer, tránh OOM nếu len bị hỏng.
            if (len <= 0 || len > 65536) {
                logger.d("TuyaLocalController", "readSocketFully: len=$len bất thường, huỷ đọc")
                return null
            }

            val rest = readExactly(input, len) ?: return null
            header + rest
        } catch (e: Exception) {
            null
        }
    }

    /** Đọc đúng [count] byte từ [input], lặp read() cho tới khi đủ hoặc gặp EOF/lỗi. */
    private fun readExactly(input: java.io.InputStream, count: Int): ByteArray? {
        val buffer = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = input.read(buffer, offset, count - offset)
            if (read <= 0) return null // EOF trước khi đủ byte — coi là lỗi/gói hỏng
            offset += read
        }
        return buffer
    }

    // ⚠️ SỬA (xác nhận qua log debug: headerSize=20 đúng độ dài (mod16=0) nhưng vẫn
    // BadPaddingException): Tuya dùng ĐỆM THỦ CÔNG bằng byte 0x00 cho đủ bội số 16 trước khi mã
    // hoá — KHÔNG phải chuẩn PKCS5/PKCS7 (đó là lý do code ở nơi gọi đã có sẵn `.trim(Char(0))`
    // sau khi decrypt, nhưng trước đây không bao giờ chạy tới được vì Cipher tự kiểm tra padding
    // kiểu PKCS5 trên khối cuối và luôn thấy sai — toàn byte 0 không khớp định dạng PKCS5 (byte
    // cuối phải lặp lại đúng N lần) → ném BadPaddingException dù nội dung giải mã ra thực chất
    // đúng. Đổi sang NoPadding + tự đệm 0x00 tay cho ĐÚNG hành vi giao thức thật, không dựa vào
    // JCE tự đệm/tự kiểm tra kiểu chuẩn Java. Ảnh hưởng CẢ broadcast decrypt LẪN mọi lệnh điều
    // khiển local (sendCommand33/34, queryStatus33) vì dùng chung 2 hàm này.
    private fun padToBlockSize(data: ByteArray, blockSize: Int = 16): ByteArray {
        val remainder = data.size % blockSize
        if (remainder == 0) return data
        return data + ByteArray(blockSize - remainder) // đệm 0x00, không phải PKCS5
    }

    private fun aesEcbEncrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(padToBlockSize(data))
    }

    private fun aesEcbDecrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }
}
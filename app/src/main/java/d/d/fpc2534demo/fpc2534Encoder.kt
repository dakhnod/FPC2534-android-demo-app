package d.d.fpc2534demo;

import d.d.fpc2534demo.fpc2534Encoder.DeviceState.APP_FW_READY
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@ExperimentalStdlibApi
class fpc2534Encoder {
    private val crypto = Cipher.getInstance("AES/GCM/NoPadding")
    @ExperimentalStdlibApi
    var key: SecretKey? = null
    enum class Command(val code: Short) {
        STATUS(0x0040),
        VERSION(0x0041),
        BIST(0x0044),
        CAPTURE(0x0050),
        ABORT(0x0052),
        IMAGE_DATA(0x0053),
        ENROLL(0x0054),
        IDENTIFY(0x0055),
        LIST_TEMPLATES(0x0060),
        DELETE_TEMPLATE(0x0061),
        GET_TEMPLATE_DATA(0x0062),
        PUT_TEMPLATE_DATA(0x0063),
        GET_SYSTEM_CONFIG(0x006A),
        SET_SYSTEM_CONFIG(0x006B),
        RESET(0x0072),
        SET_CRYPTO_KEY(0x0083),
        SET_DBG_LOG_LEVEL(0x00B0),
        FACTORY_RESET(0x00FA),
        DATA_GET(0x0101),
        DATA_PUT(0x0102),
        NAVIGATION(0x0200),
        NAVIGATION_PS(0x0201),
        GPIO_CONTROL(0x0300);

        companion object {
            // Handy for converting a hex response back into an Enum
            fun fromCode(code: Short): Command? = entries.find { it.code == code }
        }
    }

    enum class DeviceState(val value: Short) {
        APP_FW_READY(0x0001),
        SECURE_INTERFACE(0x0002),
        CAPTURE(0x0004),
        IMAGE_AVAILABLE(0x0010),
        DATA_TRANSFER(0x0040),
        FINGER_DOWN(0x0080),
        SYS_ERROR(0x0400),
        ENROLL(0x1000),
        IDENTIFY(0x2000),
        NAVIGATION(0x4000);

        companion object {
            fun fromCode(code: Short): Array<DeviceState> {
                var count = 0
                for (i in 0..<16) {
                    if(code.toInt().shr(i).and(1) == 1) {
                        count += 1
                    }
                }

                var result = Array(count, { APP_FW_READY })
                var index = 0

                for (flag in DeviceState.entries) {
                    if (code.toInt().and(flag.value.toInt()) != 0) {
                        result[index++] = flag
                    }
                }

                return result
            }
        }
    }

    enum class DeviceEvent {
        EVENT_NONE,
        EVENT_IDLE,
        FILLER,
        EVENT_FINGER_DETECT,
        EVENT_FINGER_LOST,
        EVENT_IMAGE_READY,
        EVENT_CMD_FAILED;

        companion object {
            fun fromCode(code: Short): DeviceEvent {
                return DeviceEvent.entries[code.toInt()]
            }
        }
    }

    fun decrypt(data: ByteArray): ByteBuffer {
        if(this.key == null) {
            return ByteBuffer.wrap(data)
        }

        val spec = GCMParameterSpec(128, data, 8, 12)
        crypto.init(Cipher.DECRYPT_MODE, this.key, spec)

        crypto.updateAAD(data, 0, 8)

        val encrypted = ByteBuffer
            .allocate(data.size - (20))
            .put(data, 8 + 12 + 16, data.size - (8 + 12 + 16))
            .put(data, 20, 16)
            .array()

        return ByteBuffer
            .allocate(data.size - (12 + 16))
            .put(data, 0, 8)
            .put(crypto.doFinal(encrypted))
            .order(ByteOrder.LITTLE_ENDIAN)
    }

    private fun createPacket(payload: ByteArray): ByteArray {
        var payloadSize = payload.size
        var flags = 0x10

        if(this.key != null) {
            payloadSize += 28
            flags = flags.or(0x01)
        }

        val header = ByteBuffer
            .allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(0x04)
            .putShort(0x11)
            .putShort(flags.toShort())
            .putShort(payloadSize.toShort())
            .array()

        if(this.key == null) {
            return ByteBuffer
                .allocate(8 + payload.size) // 28 for encryption
                .order(ByteOrder.LITTLE_ENDIAN)
                .put(header)
                .put(payload)
                .array()
        }

        crypto.init(Cipher.ENCRYPT_MODE, this.key)

        crypto.updateAAD(header)

        val encrypted = crypto.doFinal(payload)

        return ByteBuffer
            .allocate(8 + payload.size + 28) // 28 for encryption
            .order(ByteOrder.LITTLE_ENDIAN)
            .put(header)
            .put(crypto.iv)
            .put(encrypted, encrypted.size - 16, 16) // CMAC
            .put(encrypted, 0, encrypted.size - 16)
            .array()
    }

    fun createRequest(cmd: Command, args: ByteArray = ByteArray(0)): ByteArray {
        return createPacket(
            ByteBuffer
                .allocate(4 + args.size)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(cmd.code)
                .putShort(0x11)
                .put(args)
                .array()
        )
    }
}

@file:OptIn(ExperimentalStdlibApi::class, ExperimentalStdlibApi::class)

package d.d.fpc2534demo

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothAssignedNumbers
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresPermission
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.min
import kotlin.math.round
import androidx.core.graphics.set
import androidx.core.graphics.createBitmap
import d.d.fpc2534demo.fpc2534Encoder.Command
import d.d.fpc2534demo.fpc2534Encoder.DeviceState
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.spec.SecretKeySpec

class MainActivity : AppCompatActivity() {
    private val TAG = "MainActivity"

    private val UUID_SPI_SERVICE    = UUID.fromString("383f0000-7947-d815-7830-14f1584109c5")
    private val UUID_SPI_WRITE_CHAR = UUID.fromString("383f0001-7947-d815-7830-14f1584109c5")
    private val UUID_SPI_READ_CHAR  = UUID.fromString("383f0002-7947-d815-7830-14f1584109c5")

    private val UUID_CCCD           = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var connectButton: Button? = null
    private var abortButton: Button? = null
    private var getImageButton: Button? = null
    private var blinkButton: Button? = null
    private var stateText: TextView? = null
    private var fingerprintImage: ImageView? = null

    val fingerprintBitmap = createBitmap(96, 100)
    var currentPixelIndex = 0
    var outFileStream: FileOutputStream? = null

    private var fpcEncoder = fpc2534Encoder()

    private var mtu = 20
    private var imageDataSize = 0

    private var gatt: BluetoothGatt? = null

    enum class AppState(val description: String? = null) {
        DISCONNECTED("Disconnected"),
        IDLE("Waiting for command"),
        WAITING_FINGER_DOWN("Place finger on sensor"),
        WAITING_IMAGE("Waiting for image capture"),
        WAITING_FINGER_UP("Image available, remove finger from sensor"),
        WAITING_DATA("Waiting for image data")
    }

    private var appState = AppState.IDLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        connectButton = findViewById(R.id.button_connect)
        abortButton = findViewById(R.id.button_abort)
        getImageButton = findViewById(R.id.button_get_image)
        blinkButton = findViewById(R.id.button_blink)
        stateText = findViewById(R.id.text_state)
        fingerprintImage = findViewById(R.id.image_fingerprint)

        fingerprintImage?.setImageBitmap(fingerprintBitmap)

        connectButton?.setOnClickListener {
            // connectButton?.isClickable = false
            connectBluetooth()
        }

        getImageButton?.setOnClickListener {
            gatt?.let {
                sendPayload(it, Command.CAPTURE)
            }
        }

        abortButton?.setOnClickListener {
            gatt?.let {
                sendPayload(it, Command.ABORT)
            }
        }

        blinkButton?.setOnClickListener {
            gatt?.let {
                sendGpioasmPayload(
                    it,
                    listOf(0x00, 0x80, 0x00, 0x01, 0xf1, 0x20, 0x64, 0x01, 0xf4, 0x20, 0x64, 0xb0, 0x02, 0x05, 0x01, 0xf0)
                )
            }
        }

        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }

        val keyAlias = "FPC2534_KEY"

        // uncomment to commit your own key
        /*
        createKeyEntry(
            keyStore,
            keyAlias,
            "0000000000000000000000000000000000000000000000000000000000000000".hexToByteArray()
        )
        */

        if(keyStore.containsAlias(keyAlias)) {
            val keyEntry = keyStore.getEntry(keyAlias, null) as KeyStore.SecretKeyEntry
            fpcEncoder.key = keyEntry.secretKey
        }
    }

    private fun createKeyEntry(keyStore: KeyStore, keyAlias: String, key: ByteArray) {
        val protection = KeyProtection.Builder(
            KeyProperties.PURPOSE_ENCRYPT.or(KeyProperties.PURPOSE_DECRYPT)
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(false) // REMOVE!!
            .build()

        keyStore.setEntry(
            keyAlias,
            KeyStore.SecretKeyEntry(
                SecretKeySpec(
                    key,
                    "AES"
                )
            ),
            protection
        )
    }

    private fun setStateText(text: String, error: Boolean = false) {
        runOnUiThread {
            stateText?.text = text
            stateText?.setTextColor(if(error) Color.RED else Color.WHITE)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendPayload(gatt: BluetoothGatt, cmd: Command, args: ByteArray = ByteArray(0)) {
        val spiService = gatt.getService(UUID_SPI_SERVICE)
        val writeCharacteristic = spiService.getCharacteristic(UUID_SPI_WRITE_CHAR)

        val payload = fpcEncoder.createRequest(cmd, args)

        writeCharacteristic?.let {
            gatt.writeCharacteristic(
                writeCharacteristic,
                payload,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchData (gatt: BluetoothGatt, chunkSize: Int) {
        Handler(Looper.getMainLooper()).postDelayed({
            val actualChunkSize = min(150, chunkSize)

            val payload = ByteBuffer
                .allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(actualChunkSize)
                .array()

            sendPayload(gatt, Command.DATA_GET, payload)
        }, 10)
    }

    private fun setAppState(state: AppState) {
        appState = state

        state.description?.let { setStateText(it) }
    }

    @SuppressLint("MissingPermission")
    private fun sendGpioasmPayload(gatt: BluetoothGatt, payload: List<Int>) {
        val gpioASMChar = gatt
            .getService(UUID.fromString("b1190000-2a74-d5a2-784f-c1cdb3862ab0"))
            ?.getCharacteristic(UUID.fromString("b1190001-2a74-d5a2-784f-c1cdb3862ab0"))
        gpioASMChar?.let {
            gatt.writeCharacteristic(
                gpioASMChar,
                (payload.map { it.toByte() }).toByteArray(),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleStateChange(gatt: BluetoothGatt, buffer: ByteBuffer) {
        val event = fpc2534Encoder.DeviceEvent.fromCode(buffer.getShort(12))
        val states = DeviceState.fromCode(buffer.getShort(14))

        Log.d(TAG, "onCharacteristicChanged: event: $event")

        for (s in states) {
            Log.d(TAG, "onCharacteristicChanged: state: $s")
        }

        when (appState) {
            AppState.IDLE -> {
                if (event == fpc2534Encoder.DeviceEvent.EVENT_NONE) {
                    if (states.contains(DeviceState.CAPTURE)) {
                        setAppState(AppState.WAITING_FINGER_DOWN)
                    }
                }
            }
            AppState.WAITING_FINGER_DOWN -> {
                if (event == fpc2534Encoder.DeviceEvent.EVENT_FINGER_DETECT) {
                    setAppState(AppState.WAITING_IMAGE)
                }
            }
            AppState.WAITING_IMAGE -> {
                if (event == fpc2534Encoder.DeviceEvent.EVENT_IMAGE_READY) {

                    sendGpioasmPayload(
                        gatt,
                        listOf(0x00, 0x80, 0x00, 0x01, 0xfd, 0x20, 0x64, 0x01, 0xfc, 0x20, 0x64, 0xb0, 0x02, 0x05)
                    )

                    setAppState(AppState.WAITING_FINGER_UP)
                }
            }
            AppState.WAITING_FINGER_UP -> {
                if (event == fpc2534Encoder.DeviceEvent.EVENT_FINGER_LOST) {
                    sendPayload(
                        gatt,
                        Command.IMAGE_DATA,
                        listOf(0x02.toByte(), 0x00, 0x00, 0x00).toByteArray()
                    )
                }
            }
            else -> {}
        }
    }

    private fun handleImageDataReady(gatt: BluetoothGatt, buffer: ByteBuffer) {
        imageDataSize = buffer.getInt(12)
        // val imageWidth = buffer.getShort()
        // val imageHeight = buffer.getShort()
        // val maxChunkSize = buffer.getInt()

        currentPixelIndex = 0
        outFileStream = openFileOutput(System.currentTimeMillis().toString() + ".data", MODE_PRIVATE)

        fetchData(gatt, imageDataSize)
    }

    private fun handleImageData(gatt: BluetoothGatt, buffer: ByteBuffer) {
        buffer.position(12)

        val remaining = buffer.getInt()
        val chunkSize = buffer.getInt()

        runOnUiThread({
            for (i in 0 ..< chunkSize) {
                val pixelIndex = i + currentPixelIndex
                val x = pixelIndex % 96
                val y = pixelIndex / 96

                val pixel = buffer[20 + i]

                fingerprintBitmap[x, y] =
                    Color.rgb(pixel.toInt(), pixel.toInt(), pixel.toInt())
            }

            currentPixelIndex += chunkSize
            val progress = currentPixelIndex.toFloat() / imageDataSize
            val percent = round(progress * 100.0).toInt()
            setStateText("Download: $percent%")
        })

        outFileStream?.write(buffer.array(), 20, chunkSize)

        if(remaining > 0) {
            fetchData(gatt, remaining)

            return
        }
        outFileStream?.close()
        outFileStream = null
        setAppState(AppState.IDLE)
    }

    private fun connectBluetooth() {
        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager

        val bluetoothAdapter = bluetoothManager.adapter

        // val scanner = bluetoothAdapter.bluetoothLeScanner

        val connectCallback = object: BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                super.onConnectionStateChange(gatt, status, newState)

                Log.d(TAG, "onConnectionStateChange: $status, $newState")

                when (newState) {
                    BluetoothGatt.STATE_CONNECTED -> {
                        if (ActivityCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            return
                        }

                        gatt?.requestMtu(512)
                    }
                    BluetoothGatt.STATE_DISCONNECTED -> {
                        setAppState(AppState.DISCONNECTED)
                    }
                }
            }

            @SuppressLint("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                super.onServicesDiscovered(gatt, status)

                if(gatt == null) return;

                val spiService = gatt.getService(UUID_SPI_SERVICE)
                val readCharacteristic = spiService.getCharacteristic(UUID_SPI_READ_CHAR)

                gatt.writeDescriptor(
                    readCharacteristic.getDescriptor(UUID_CCCD),
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                )
                gatt.setCharacteristicNotification(readCharacteristic, true)

                Log.d(TAG, "onServicesDiscovered: $status")
            }

            @SuppressLint("MissingPermission")
            override fun onMtuChanged(gatt: BluetoothGatt?, mtu_: Int, status: Int) {
                super.onMtuChanged(gatt, mtu_, status)

                Log.d(TAG, "onMtuChanged: $mtu_")

                mtu = mtu_

                gatt?.discoverServices()
            }

            @SuppressLint("MissingPermission")
            override fun onDescriptorWrite(
                gatt_: BluetoothGatt?,
                descriptor: BluetoothGattDescriptor?,
                status: Int
            ) {
                super.onDescriptorWrite(gatt_, descriptor, status)

                Log.d(TAG, "onDescriptorWrite: $status")

                if(gatt_ == null) return

                gatt = gatt_
                setAppState(AppState.IDLE)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt?,
                characteristic: BluetoothGattCharacteristic?,
                status: Int
            ) {
                super.onCharacteristicWrite(gatt, characteristic, status)

                Log.d(TAG, "onCharacteristicWrite: $characteristic $status")
            }

            @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                super.onCharacteristicChanged(gatt, characteristic, value)

                Log.d(TAG, "onCharacteristicChanged: $characteristic, ${value.toHexString()}")

                val buffer = fpcEncoder.decrypt(value)

                val responseCommand = fpc2534Encoder.Command.fromCode(buffer.getShort(8))
                Log.d(TAG, "onCharacteristicChanged: responseCommand: $responseCommand")

                when (responseCommand) {
                    Command.STATUS -> {
                        handleStateChange(gatt, buffer)
                    }
                    Command.IMAGE_DATA -> {
                        handleImageDataReady(gatt, buffer)
                    }
                    Command.DATA_GET -> {
                        handleImageData(gatt, buffer)
                    }
                    else -> {}
                }

                Log.d(TAG, "onCharacteristicChanged: ${value[14]}")
            }
        }

        val callback = object: ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                super.onScanResult(callbackType, result)

                if(result == null) {
                    return
                }

                Log.d(TAG, "onScanResult: $result")

                setStateText("Found device")

                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    setStateText("Missing connect permission", true)
                    return
                }
                val remoteDevice = bluetoothAdapter.getRemoteLeDevice(result.device.address, result.device.type)

                remoteDevice.connectGatt(this@MainActivity, false, connectCallback)
            }

            override fun onScanFailed(errorCode: Int) {
                super.onScanFailed(errorCode)

                connectButton?.isClickable = true

                setStateText("Scan failed", true)
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "missing bluetooth permissions", Toast.LENGTH_LONG).show()
            return
        }

        setStateText("Connecting...")

        val remoteDevice = bluetoothAdapter.getRemoteLeDevice("CB:6F:0F:38:A5:24", BluetoothDevice.ADDRESS_TYPE_RANDOM)

        if (ActivityCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            setStateText("Missing connect permission", true)
            return
        }

        remoteDevice.connectGatt(this@MainActivity, false, connectCallback)
        /*
        scanner.startScan(
            listOf(
                ScanFilter.Builder()
                    .setDeviceAddress("CB:6F:0F:38:A5:24")
                    .build()
            ),
            ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                .build(),
            callback
        )
        */
    }
}
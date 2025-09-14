package com.example.dashboard

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.github.eltonvs.obd.command.ObdResponse
import com.github.eltonvs.obd.command.engine.MassAirFlowCommand
import com.github.eltonvs.obd.command.engine.RPMCommand
import com.github.eltonvs.obd.command.fuel.FuelLevelCommand
import com.github.eltonvs.obd.command.temperature.EngineCoolantTemperatureCommand
import com.github.eltonvs.obd.connection.ObdDeviceConnection
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID

data class VehicleDataSample(
    val timestamp: Long = System.currentTimeMillis(),
    var speedKmh: Float = 0f,
    var rpm: Int = 0,
    var fuelLevelPct: Float? = null,
    var fuelRateLph: Float? = null,
    var coolantTempC: Float? = null
)

class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivityIntegrated"
    private val OBD_STANDARD_SERIAL_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private lateinit var tvSpeed: TextView
    private lateinit var tvRpm: TextView
    private lateinit var tvInstCons: TextView
    private lateinit var tvFuelLevel: TextView
    private lateinit var tvCoolantTemp: TextView
    private lateinit var tvAvgCons: TextView
    private lateinit var tvRange: TextView

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private var obdSocket: BluetoothSocket? = null
    private var obdConnection: ObdDeviceConnection? = null
    private var obdJob: Job? = null
    private var mockDataJob: Job? = null

    private var selectedMacAddress: String? = null
    private var intentToStartRealDeviceAfterPermission: Boolean = false

    private val samples = ArrayDeque<VehicleDataSample>()
    private var totalDistanceKmForAvg = 0.0
    private var totalFuelLForAvg = 0.0
    private val tankCapacityLiters = 50.0

    private val requestBluetoothPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.entries.all { it.value }
            if (allGranted) {
                Toast.makeText(this, "Permissions Bluetooth accordées.", Toast.LENGTH_SHORT).show()
                if (intentToStartRealDeviceAfterPermission) {
                    selectedMacAddress?.let { connectAndStartObdRealDevice(it) }
                    intentToStartRealDeviceAfterPermission = false
                }
            } else {
                Toast.makeText(this, "Permissions Bluetooth nécessaires refusées.", Toast.LENGTH_LONG).show()
                intentToStartRealDeviceAfterPermission = false
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        resetUI()
    }

    private fun initializeViews() {
        tvSpeed = findViewById(R.id.tvSpeed)
        tvRpm = findViewById(R.id.tvRpm)
        tvInstCons = findViewById(R.id.tvInstCons)
        tvFuelLevel = findViewById(R.id.tvFuelLevel)
        tvCoolantTemp = findViewById(R.id.tvCoolantTemp)
        tvAvgCons = findViewById(R.id.tvAvgCons)
        tvRange = findViewById(R.id.tvRange)
    }

    private fun requestBluetoothPermissions() {
        val permissionsToRequest = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_SCAN)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissionsToRequest.add(Manifest.permission.BLUETOOTH)
            permissionsToRequest.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        if (permissionsToRequest.isNotEmpty()){
            requestBluetoothPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectAndStartObdRealDevice(macAddress: String) {
        val btAdapter = bluetoothAdapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            Toast.makeText(this, "Bluetooth non activé.", Toast.LENGTH_SHORT).show()
            return
        }
        Log.d(TAG, "Tentative de connexion à $macAddress")
        Toast.makeText(this, "Connexion à l'OBD...", Toast.LENGTH_SHORT).show()

        obdJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val device: BluetoothDevice = btAdapter.getRemoteDevice(macAddress)
                obdSocket = device.createRfcommSocketToServiceRecord(OBD_STANDARD_SERIAL_UUID)
                obdSocket!!.connect()
                val input = obdSocket!!.inputStream
                val output = obdSocket!!.outputStream
                try {
                    output.write("ATZ\r".toByteArray())
                    output.flush()

                    val reader = BufferedReader(InputStreamReader(input))
                    val sb = StringBuilder()
                    val deadline = System.currentTimeMillis() + 1000
                    while (System.currentTimeMillis() < deadline) {
                        if (reader.ready()) {
                            val line = reader.readLine()
                            if (line != null) sb.append(line).append("\n")
                        } else {
                            delay(50)
                        }
                    }
                    Log.i(TAG, "ELM reply (ATZ): ${sb.toString().trim()}")
                } catch (e: Exception) {
                    Log.e(TAG, "ATZ test failed: ${e.message}", e)
                }

                obdConnection = ObdDeviceConnection(obdSocket!!.inputStream, obdSocket!!.outputStream)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Connecté à l'OBD!", Toast.LENGTH_SHORT).show()
                    Log.i(TAG, "Connecté avec succès à l'appareil OBD.")
                }
                startObdDataPolling()
            } catch (e: IOException) {
                Log.e(TAG, "Erreur de connexion Bluetooth: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Échec connexion: ${e.message}", Toast.LENGTH_LONG).show()
                    stopObdOperations(resetButtons = true)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Erreur de permission lors de la connexion: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Erreur permission: ${e.message}", Toast.LENGTH_LONG).show()
                    stopObdOperations(resetButtons = true)
                    requestBluetoothPermissions()
                }
            }
        }
    }

    private suspend fun startObdDataPolling() {
        val connection = obdConnection ?: return
        Log.d(TAG, "Démarrage de la lecture des données OBD.")
        while (obdSocket?.isConnected == true) {
            val currentSample = VehicleDataSample()
            try {
                val speedResponse: ObdResponse = connection.run(com.github.eltonvs.obd.command.engine.SpeedCommand())
                val rpmResponse: ObdResponse = connection.run(RPMCommand())
                val coolantTempResponse: ObdResponse = connection.run(EngineCoolantTemperatureCommand())
                val fuelLevelResponse: ObdResponse = connection.run(FuelLevelCommand())
                val fuelRateResponse: ObdResponse? = try { connection.run(FuelLevelCommand()) } catch (_: Exception) { null }

                currentSample.speedKmh = speedResponse.value.toFloatOrNull() ?: currentSample.speedKmh
                currentSample.rpm = rpmResponse.value.toIntOrNull() ?: currentSample.rpm
                currentSample.coolantTempC = coolantTempResponse.value.toFloatOrNull() ?: currentSample.coolantTempC
                currentSample.fuelLevelPct = fuelLevelResponse.value.toFloatOrNull() ?: currentSample.fuelLevelPct
                currentSample.fuelRateLph = fuelRateResponse?.value?.toFloatOrNull()

                if (currentSample.fuelRateLph == null) {
                    try {
                        val mafResponse: ObdResponse = connection.run(MassAirFlowCommand())
                        val mafGramsPerSecond = mafResponse.value.toFloatOrNull()
                        if (mafGramsPerSecond != null) {
                            currentSample.fuelRateLph = (mafGramsPerSecond * 3600) / 750f
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Impossible d'obtenir MAF: ${e.message}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Erreur lors de la lecture d'une commande OBD: ${e.message}")
                if (obdSocket?.isConnected != true) {
                    Log.e(TAG, "Socket OBD déconnecté pendant la lecture.")
                    break
                }
            }

            withContext(Dispatchers.Main) {
                updateUI(currentSample)
            }
            delay(1000)
        }
        Log.d(TAG, "Arrêt de la lecture des données OBD (boucle terminée).")
        if (obdSocket?.isConnected != true) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Connexion OBD perdue.", Toast.LENGTH_LONG).show()
                stopObdOperations(resetButtons = true)
            }
        }
    }


    private fun stopObdOperations(resetButtons: Boolean = true) {
        Log.d(TAG, "Arrêt de toutes les opérations OBD/Mock.")
        mockDataJob?.cancel()
        mockDataJob = null

        obdJob?.cancel()
        obdJob = null

        try {
            obdSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Erreur lors de la fermeture du socket OBD: ${e.message}")
        }
        obdSocket = null
        obdConnection = null

        if (resetButtons) {
            runOnUiThread {
                resetUI()
            }
        }
        Toast.makeText(this, "Opérations OBD arrêtées.", Toast.LENGTH_SHORT).show()
    }


    private fun updateUI(sample: VehicleDataSample) {
        tvSpeed.text = "Vitesse: ${sample.speedKmh.toInt()} km/h"
        tvRpm.text = "RPM: ${sample.rpm}"

        val instConsL100km: Float? = if (sample.speedKmh > 1 && sample.fuelRateLph != null && sample.fuelRateLph!! > 0) {
            (sample.fuelRateLph!! / sample.speedKmh) * 100
        } else {
            null
        }
        tvInstCons.text = "Conso instant: ${instConsL100km?.let { "%.1f L/100km".format(it) } ?: (sample.fuelRateLph?.let { "%.1f L/h".format(it) } ?: "--")}"
        tvFuelLevel.text = "Jauge: ${sample.fuelLevelPct?.let { "%.1f %%".format(it) } ?: "--"}"
        tvCoolantTemp.text = "Temp eau: ${sample.coolantTempC?.let { "%.1f °C".format(it) } ?: "--"}"

        val dtHours = 1.0 / 3600.0
        val distanceKmDelta = sample.speedKmh * dtHours
        val fuelUsedLitersDelta = (sample.fuelRateLph?.toDouble() ?: 0.0) * dtHours

        if (distanceKmDelta > 0.0001 || fuelUsedLitersDelta > 0.00001) {
            samples.addLast(sample)
            totalDistanceKmForAvg += distanceKmDelta
            totalFuelLForAvg += fuelUsedLitersDelta
        }

        val maxSamples = 1000
        val maxDistanceKmForRollingAverage = 100.0
        while (samples.size > maxSamples || (totalDistanceKmForAvg > maxDistanceKmForRollingAverage && samples.isNotEmpty())) {
            val s0 = samples.removeFirst()
            val d0 = s0.speedKmh * dtHours
            val f0 = (s0.fuelRateLph?.toDouble() ?: 0.0) * dtHours
            totalDistanceKmForAvg = (totalDistanceKmForAvg - d0).coerceAtLeast(0.0)
            totalFuelLForAvg = (totalFuelLForAvg - f0).coerceAtLeast(0.0)
            if (samples.isEmpty()) break
        }

        val avgConsumption: Double? = if (totalDistanceKmForAvg > 0.01) {
            (totalFuelLForAvg / totalDistanceKmForAvg) * 100.0
        } else null
        tvAvgCons.text = "Conso moy: ${avgConsumption?.let { "%.1f L/100km".format(it) } ?: "--"}"

        val autonomyKm: Double? = if (avgConsumption != null && avgConsumption > 0.1 && sample.fuelLevelPct != null) {
            val litersLeft = tankCapacityLiters * (sample.fuelLevelPct!! / 100.0)
            (litersLeft / avgConsumption) * 100.0
        } else null
        tvRange.text = "Autonomie: ${autonomyKm?.let { "%.0f km".format(it) } ?: "--"}"
    }

    private fun resetUI() {
        tvSpeed.text = "Vitesse: -- km/h"
        tvRpm.text = "RPM: --"
        tvInstCons.text = "Conso instant: -- L/100km"
        tvFuelLevel.text = "Jauge: -- %"
        tvCoolantTemp.text = "Temp eau: -- °C"
        tvAvgCons.text = "Conso moy: -- L/100km"
        tvRange.text = "Autonomie: -- km"

        samples.clear()
        totalDistanceKmForAvg = 0.0
        totalFuelLForAvg = 0.0
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy: Arrêt des opérations OBD.")
        stopObdOperations(resetButtons = false)
    }
}

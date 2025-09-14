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
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.github.eltonvs.obd.command.ObdCommand
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


data class VehicleDataSample(
    val timestamp: Long = System.currentTimeMillis(),
    var speedKmh: Float = 0f,
    var rpm: Int = 0,
    var fuelLevelPct: Float? = null,
    var fuelRateLph: Float? = null,
    var coolantTempC: Float? = null
)

class MainActivity : AppCompatActivity() {
    private lateinit var btnSniffer: Button // Ajoute cette variable
    private var snifferJob: Job? = null     // Job pour notre sniffer
    private lateinit var logs: TextView
    private lateinit var spinnerMac: Spinner
    private lateinit var btnConnect: Button

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

        println("!!!!!!!!!!!!!!!! dans le onCreate")
        initializeViews()
        resetUI()

        spinnerMac = findViewById(R.id.spinnerMac)
        btnConnect = findViewById(R.id.btnConnect)
        btnSniffer = findViewById(R.id.btnSniffer) // Initialise le nouveau bouton
        logs = findViewById(R.id.logs)

        btnConnect.setOnClickListener {
            val selectedMac = spinnerMac.selectedItem.toString()
            Log.i("MainActivity", "Connexion avec $selectedMac")
            stopAllJobs() // Arrête les autres tâches avant de lancer la connexion principale
            connectAndStartObdRealDevice(selectedMac)
        }

        // Le listener pour notre outil de diagnostic
        btnSniffer.setOnClickListener {
            val selectedMac = spinnerMac.selectedItem.toString()
            // Ceci est sur le thread UI - OK
            logs.text = "${logs.text}\nTentative de lancement du sniffer sur $selectedMac..."
            stopAllJobs() // Arrête les autres tâches avant de lancer le sniffer
            startRawDataSniffer(selectedMac)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startRawDataSniffer(macAddress: String) {
        val btAdapter = bluetoothAdapter
        if (btAdapter == null || !btAdapter.isEnabled) {
            Toast.makeText(this, "Bluetooth non activé.", Toast.LENGTH_SHORT).show()
            return
        }

        snifferJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                // Modification de l'UI: basculer vers le thread principal
                withContext(Dispatchers.Main) {
                    logs.text = "${logs.text} \ndébut du snif sur $macAddress" // Ajout de macAddress pour clarté
                    Toast.makeText(this@MainActivity, "Sniffer: Connexion...", Toast.LENGTH_SHORT).show()
                }

                val device: BluetoothDevice = btAdapter.getRemoteDevice(macAddress)
                logs.text = "${logs.text} \ndevice check"
                val socket = device.createInsecureRfcommSocketToServiceRecord(OBD_STANDARD_SERIAL_UUID)
                logs.text = "${logs.text} \nsocket check"
                socket.connect()
                logs.text = "${logs.text} \nsocket connect check"

                withContext(Dispatchers.Main) {
                    logs.text = "${logs.text} \nConnecté et initialisé!"
                    Toast.makeText(this@MainActivity, "Sniffer: Connecté ! J'écoute...", Toast.LENGTH_LONG).show()
                }

                val inputStream = socket.inputStream
                logs.text = "${logs.text} \ninputStream check"
                val reader = BufferedReader(InputStreamReader(inputStream))
                logs.text = "${logs.text} \nreader check"

                while (isActive) {
                    if (reader.ready()) {
                        val line = reader.readLine()
                        // Modification de l'UI: basculer vers le thread principal
                        withContext(Dispatchers.Main) {
                            logs.text = "${logs.text}\nSniffer data: $line" // Amélioration du format du log
                        }
                    } else {
                        delay(100)
                    }
                }
            } catch (e: Exception) {
                // Modification de l'UI: basculer vers le thread principal
                withContext(Dispatchers.Main) {
                    logs.text = "${logs.text}\nErreur du sniffer: ${e.message}"
                    Toast.makeText(this@MainActivity, "Erreur Sniffer: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                // S'assurer que le socket est fermé même en cas d'erreur ou d'annulation
                try {
                    obdSocket?.close() // Si c'est le même socket utilisé par le sniffer et l'OBD.
                    // Idéalement, le sniffer aurait son propre socket.
                    // Pour l'instant, on suppose qu'il peut être partagé ou que stopAllJobs s'en charge.
                } catch (ioe: IOException) {
                    Log.w(TAG, "Erreur à la fermeture du socket du sniffer: ${ioe.message}")
                }
            }
        }
    }

    // Une petite fonction utilitaire pour arrêter les tâches en cours avant d'en lancer une autre
    private fun stopAllJobs() {
        obdJob?.cancel()
        snifferJob?.cancel()
        try {
            obdSocket?.close()
        } catch (e: IOException) { /* ignore */ }
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

    // Dans MainActivity.kt

// ... (imports et début de la classe)

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
                obdSocket = device.createInsecureRfcommSocketToServiceRecord(OBD_STANDARD_SERIAL_UUID)
                obdSocket!!.connect()

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Socket Connecté! Initialisation...", Toast.LENGTH_SHORT).show()
                }

                // ÉTAPE 1: On initialise le dongle manuellement en utilisant les flux bruts
                val inputStream = obdSocket!!.inputStream
                val outputStream = obdSocket!!.outputStream

                val initSuccess = initializeElm327(inputStream, outputStream)
                if (!initSuccess) {
                    throw IOException("Échec de l'initialisation de l'ELM327.")
                }

                // ÉTAPE 2: L'initialisation a réussi, on peut maintenant passer les flux à la librairie
                obdConnection = ObdDeviceConnection(inputStream, outputStream)

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Connecté et initialisé!", Toast.LENGTH_SHORT).show()
                    Log.i(TAG, "Connecté avec succès à l'appareil OBD.")
                }
                startObdDataPolling()

            } catch (e: Exception) {
                Log.e(TAG, "Erreur de connexion ou d'initialisation: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Échec connexion: ${e.message}", Toast.LENGTH_LONG).show()
                    stopObdOperations(resetButtons = true)
                    if (e is SecurityException) {
                        requestBluetoothPermissions()
                    }
                }
            }
        }
    }

    /**
     * Envoie des commandes AT à l'adaptateur et lit les réponses pour le configurer.
     * C'est une communication bas niveau, directe avec l'appareil.
     */
    private suspend fun initializeElm327(inputStream: java.io.InputStream, outputStream: java.io.OutputStream): Boolean {
        val initCommands = listOf("ATZ", "ATE0", "ATL0", "ATSP0")
        val reader = BufferedReader(InputStreamReader(inputStream))

        try {
            // On lit et vide tout ce qui pourrait être dans le buffer de réception au début
            while (reader.ready()) {
                Log.d(TAG, "Nettoyage buffer initial: ${reader.readLine()}")
            }

            for (command in initCommands) {
                Log.d(TAG, "Envoi de la commande d'init: $command")
                // On envoie la commande suivie d'un retour chariot, c'est crucial
                outputStream.write("$command\r".toByteArray())
                outputStream.flush()

                delay(200) // Laisse le temps à l'appareil de traiter

                // Maintenant, on lit la réponse. Les adaptateurs ELM327 finissent leur réponse par '>'
                val response = StringBuilder()
                val timeout = System.currentTimeMillis() + 2000 // Timeout de 2 secondes
                var line: String?

                while (System.currentTimeMillis() < timeout) {
                    if (reader.ready()) {
                        line = reader.readLine()
                        if (line != null) {
                            response.append(line).append("\n")
                            // Si la réponse contient le prompt, c'est qu'il a fini de parler
                            if (line.contains(">")) {
                                break
                            }
                        }
                    } else {
                        delay(50)
                    }
                }

                val trimmedResponse = response.toString().trim()
                Log.i(TAG, "Réponse à '$command': $trimmedResponse")

                // Si la réponse est vide ou ne contient pas "OK" (pour ATZ par exemple), on considère que c'est un échec
                if (trimmedResponse.isEmpty() || (command == "ATZ" && !trimmedResponse.contains("ELM", ignoreCase = true))) {
                    // Pour ATZ, on attend une réponse qui contient "ELM". Pour les autres, une simple réponse suffit.
                    if (command == "ATZ" && !trimmedResponse.contains("ELM", ignoreCase = true)) {
                        Log.e(TAG, "Réponse invalide pour ATZ. L'adaptateur n'est peut-être pas un ELM327.")
                        // On peut décider de continuer quand même, certains clones sont silencieux
                    } else if (trimmedResponse.isEmpty()){
                        Log.e(TAG, "Pas de réponse pour la commande $command")
                        return false
                    }
                }
            }
            Log.i(TAG, "Initialisation ELM327 réussie.")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Erreur durant l'initialisation de l'ELM327: ${e.message}", e)
            return false
        }
    }
    private suspend fun startObdDataPolling() {
        val connection = obdConnection ?: return
        println("!!!!!!!!!!!!!!!! Démarrage de la lecture des données OBD.")
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
        println("!!!!!!!!!!!!!!!! Arrêt de la lecture des données OBD (boucle terminée).")
        if (obdSocket?.isConnected != true) {
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Connexion OBD perdue.", Toast.LENGTH_LONG).show()
                stopObdOperations(resetButtons = true)
            }
        }
    }


    private fun stopObdOperations(resetButtons: Boolean = true) {
        println("!!!!!!!!!!!!!!!! Arrêt de toutes les opérations OBD/Mock.")
        mockDataJob?.cancel() // mockDataJob n'est pas utilisé dans cette version du code. À supprimer ?
        mockDataJob = null

        obdJob?.cancel()
        obdJob = null

        // Le socket utilisé par le sniffer et l'OBD est le même 'obdSocket'.
        // La fermeture ici affectera les deux. C'est implicite par stopAllJobs aussi.
        try {
            obdSocket?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Erreur lors de la fermeture du socket OBD: ${e.message}")
        }
        obdSocket = null
        obdConnection = null

        // Opérations UI
        runOnUiThread {
            if (resetButtons) {
                resetUI()
            }
            Toast.makeText(this, "Opérations OBD arrêtées.", Toast.LENGTH_SHORT).show()
        }
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
        println("!!!!!!!!!!!!!!!! onDestroy: Arrêt des opérations OBD.")
        stopObdOperations(resetButtons = false)
    }
}

package com.devcat.escootercontrol.viewmodel

import android.app.Application
import android.bluetooth.BluetoothDevice
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.devcat.escootercontrol.ble.ConnectionState
import com.devcat.escootercontrol.ble.DiscoveredDevice
import com.devcat.escootercontrol.ble.DashboardTelemetry
import com.devcat.escootercontrol.ble.ScooterBleManager
import com.devcat.escootercontrol.ble.ScooterTelemetry
import com.devcat.escootercontrol.ble.SpeedLimiterRules
import com.devcat.escootercontrol.ble.SpeedLimiterUi
import com.devcat.escootercontrol.ble.toDashboardTelemetry
import com.devcat.escootercontrol.data.FeatureFlags
import com.devcat.escootercontrol.data.FeatureSupportStore
import com.devcat.escootercontrol.data.SavedScooter
import com.devcat.escootercontrol.data.ScooterStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.devcat.escootercontrol.ble.FrameLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class ScooterViewModel(application: Application) : AndroidViewModel(application) {

    private val bleManager = ScooterBleManager(application)
    private val featureStore = FeatureSupportStore(application)
    private val scooterStore = ScooterStore(application)
    private var autoReconnectTried = false

    val connectionState: StateFlow<ConnectionState> = bleManager.connectionState
    val telemetry: StateFlow<ScooterTelemetry> = bleManager.telemetry
    val dashboardTelemetry: StateFlow<DashboardTelemetry> = telemetry
        .map { it.toDashboardTelemetry() }
        .distinctUntilChanged()
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            telemetry.value.toDashboardTelemetry()
        )
    // Lock changes are rare, so expose them separately from high-rate telemetry.
    // App-wide lock UI can observe this without making the NavHost recompose for
    // every speed/battery/status frame.
    val locked: StateFlow<Boolean?> = telemetry
        .map { it.locked }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, telemetry.value.locked)
    val scanResults: StateFlow<List<DiscoveredDevice>> = bleManager.scanResults
    val scanError: StateFlow<String?> = bleManager.scanError
    val featureFlags: StateFlow<FeatureFlags> = featureStore.flags
    val messages: SharedFlow<String> = bleManager.messages
    val frameLog: FrameLog = bleManager.frameLog
    val lastScooter: StateFlow<SavedScooter?> = scooterStore.last
    val nicknames: StateFlow<Map<String, String>> = scooterStore.nicknames
    val connectedAddress: StateFlow<String?> = bleManager.connectedAddress
    val connectedName: StateFlow<String?> = bleManager.connectedName
    val demoMode: StateFlow<Boolean> = bleManager.demoMode

    // Remembered per-scooter max speed. Kept apart from the live reading so the limiter does not
    // erase the value the dashboard shortcut has to restore.
    private val fullMaxSpeeds: StateFlow<Map<String, Int>> = scooterStore.maxSpeeds

    // Per-scooter limit the shortcut applies. A missing entry means "Unlimited".
    private val configuredSpeedLimits: StateFlow<Map<String, Int>> = scooterStore.speedLimits

    /** Write sent from the dashboard shortcut that the scooter hasn't confirmed yet. */
    private val _pendingSpeedLimiter = MutableStateFlow<Int?>(null)
    private var speedLimiterTimeout: Job? = null

    /**
     * Dashboard speed-limiter state, or null while there is nothing to toggle. Combines the
     * remembered full speed, the live reading, the configured limit and our own in-flight write.
     */
    val speedLimiter: StateFlow<SpeedLimiterUi?> = combine(
        connectedAddress,
        fullMaxSpeeds,
        configuredSpeedLimits,
        telemetry.map { it.maxSpeedLimit }.distinctUntilChanged(),
        _pendingSpeedLimiter
    ) { address, remembered, limits, reported, pending ->
        SpeedLimiterRules.ui(
            fullKmh = address?.let { remembered[it] },
            activeKmh = reported,
            limitKmh = address?.let { limits[it] },
            pendingKmh = pending
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Scooter the settings screen edits: the live connection when there is one, otherwise the
     * last scooter we saved. The limit is a phone-side preference, so it stays editable while the
     * link is down and is simply not pushed to the controller until it reconnects.
     */
    private val settingsAddress: StateFlow<String?> = combine(
        connectedAddress,
        scooterStore.last
    ) { connected, last -> connected ?: last?.address }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            connectedAddress.value ?: scooterStore.last.value?.address
        )

    /** Limit configured for the scooter in settings, or null for "Unlimited". */
    val speedLimiterLimit: StateFlow<Int?> =
        combine(settingsAddress, configuredSpeedLimits) { address, limits ->
            address?.let { limits[it] }
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            settingsAddress.value?.let { configuredSpeedLimits.value[it] }
        )

    /** Full max speed remembered for the scooter in settings, or null if not known yet. */
    val fullMaxSpeed: StateFlow<Int?> =
        combine(settingsAddress, fullMaxSpeeds) { address, remembered ->
            address?.let { remembered[it] }
        }.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            settingsAddress.value?.let { fullMaxSpeeds.value[it] }
        )

    init {
        // Remember the scooter once a connection actually works (not merely attempted).
        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                if (state == ConnectionState.READY && !bleManager.demoMode.value) {
                    val address = bleManager.connectedAddress.value
                    if (address != null) scooterStore.saveLast(address, bleManager.connectedName.value ?: "Scooter")
                }
            }
        }

        // The dashboard can show its speed-limiter shortcut as soon as the link is up, so read
        // code 60 once per session instead of waiting for the user to open Speed & performance.
        viewModelScope.launch {
            bleManager.connectionState.collect { state ->
                if (state == ConnectionState.READY) {
                    bleManager.queryMaxSpeed()
                } else {
                    // A session ended mid-write: drop the spinner, the next session re-reads.
                    speedLimiterTimeout?.cancel()
                    _pendingSpeedLimiter.value = null
                }
            }
        }

        // Keep the remembered full speed aligned with what the scooter actually reports. A reading
        // that equals the chosen limit means the limiter set it, so the remembered value must
        // survive; anything else inside the wire range is the truth.
        viewModelScope.launch {
            combine(
                bleManager.connectedAddress,
                telemetry.map { it.maxSpeedLimit }.distinctUntilChanged()
            ) { address, reported -> address to reported }
                .collect { (address, reported) ->
                    if (address == null || reported == null) return@collect
                    val remembered = scooterStore.maxSpeeds.value[address]
                    val limit = scooterStore.speedLimits.value[address]
                    val reconciled = SpeedLimiterRules.reconcile(
                        fullKmh = remembered,
                        reportedKmh = reported,
                        limitKmh = limit,
                        pendingKmh = _pendingSpeedLimiter.value
                    )
                    if (reconciled != remembered) scooterStore.setMaxSpeed(address, reconciled)
                    // Only an arrival of the value we asked for ends the "applying" state. A
                    // rejected write is handled by writeConfig's re-read and the timeout below.
                    if (reported == _pendingSpeedLimiter.value) _pendingSpeedLimiter.value = null
                }
        }
    }

    fun setFeatureSupported(update: (FeatureFlags) -> FeatureFlags) = featureStore.update(update)

    fun startScan() = bleManager.startScan()
    fun stopScan() = bleManager.stopScan()

    fun queryCurrentConfig() = bleManager.queryCurrentConfig()
    fun resetTuningToDefaults() = bleManager.resetTuningToDefaults()

    fun connect(device: BluetoothDevice, name: String? = null) = bleManager.connect(device, name)

    /** Connects to the saved scooter without scanning. False if none is saved or Bluetooth is off. */
    fun reconnectLast(): Boolean {
        val last = scooterStore.last.value ?: return false
        return bleManager.connectToAddress(last.address, last.name)
    }

    /** Called once per app launch after permissions are granted. */
    fun autoReconnectOnce() {
        if (autoReconnectTried) return
        autoReconnectTried = true
        if (connectionState.value == ConnectionState.DISCONNECTED) reconnectLast()
    }

    fun cancelConnect() = bleManager.disconnect()
    fun enterDemoMode(name: String = "Demo Scooter") = bleManager.enterDemoMode(name)

    fun renameScooter(address: String, name: String) = scooterStore.setNickname(address, name)
    fun forgetSavedScooter() {
        // Forget is intentionally stronger than Disconnect: remove the auto-reconnect target,
        // then end the live session so the user immediately sees that the action happened.
        scooterStore.forgetLast()
        autoReconnectTried = true
        bleManager.postMessage("Scooter forgotten. Auto-reconnect is off until you connect again.")
        bleManager.disconnect()
    }
    fun disconnect() = bleManager.disconnect()

    fun toggleLock() {
        val locked = telemetry.value.locked
        if (locked == null) { bleManager.postMessage("Waiting for scooter status..."); return }
        bleManager.toggleLock(locked)
    }

    fun setSpeedUnit(miles: Boolean) = bleManager.setSpeedUnit(miles)
    fun setGear(gear: Int) = bleManager.setGear(gear)
    fun queryVersions() = bleManager.queryVersions()
    fun setWorkMode(mode: Int) = bleManager.setWorkMode(mode)
    fun queryWorkMode() = bleManager.queryWorkMode()

    fun sendRaw(code: Int, data: List<Int>) = bleManager.sendRaw(code, data)

    fun findMe(activate: Boolean) = bleManager.findMe(activate)

    fun setMaxSpeed(kmh: Int) {
        // An explicit choice in Speed & performance becomes the speed the limiter restores. Store
        // it as-is: the wire range is the scooter's own bound, and reconciling a choice away would
        // leave the dashboard unable to switch back to what the user just picked.
        bleManager.connectedAddress.value?.let { address ->
            scooterStore.setMaxSpeed(address, kmh)
        }
        bleManager.setMaxSpeed(kmh)
    }

    /**
     * Dashboard quick toggle: hop between the configured full speed and the user's limit.
     * No-op while a previous write is still unconfirmed, so double taps can't race each other.
     */
    fun toggleSpeedLimiter() {
        val current = speedLimiter.value ?: return
        val target = current.toggleTargetKmh ?: return
        applySpeedLimiterTarget(target)
    }

    /**
     * Stores the limit the shortcut applies (null = Unlimited). If the limiter is currently on,
     * the new value is pushed straight away so the dashboard and the scooter never disagree.
     * Works while disconnected too: only the saved preference changes then.
     */
    fun setSpeedLimiterLimit(kmh: Int?) {
        val address = settingsAddress.value ?: return
        val limitNow = configuredSpeedLimits.value[address]
        if (kmh == limitNow) return   // nothing changed

        // Decide from the stored limit and the live reading, not from whether the dashboard
        // shortcut is currently visible: it hides while the full speed is unknown, and the scooter
        // must still be releasable in that state.
        val active = telemetry.value.maxSpeedLimit
        val atLimit = limitNow != null && active == limitNow
        val full = scooterStore.maxSpeeds.value[address]

        val target = if (atLimit) {
            when {
                // Unlimited: restore the full speed. Nothing remembered (a data wipe) falls back to
                // the top of the wire range.
                kmh == null -> full ?: SpeedLimiterRules.UNLIMITED
                // A limiter may only ever lower the speed, so an unusable choice stops limiting.
                full != null && kmh >= full -> full
                else -> kmh
            }
        } else {
            null
        }

        // Publish the "applying" state before the store write so the shortcut never briefly loses
        // its limit/active match.
        if (target != null) _pendingSpeedLimiter.value = target
        scooterStore.setSpeedLimit(address, kmh)
        if (target != null) pushSpeedLimiterTarget(target)
    }

    /** Sends a new max speed and keeps the shortcut's "applying" state until it is confirmed. */
    private fun applySpeedLimiterTarget(target: Int) {
        _pendingSpeedLimiter.value = target
        pushSpeedLimiterTarget(target)
    }

    private fun pushSpeedLimiterTarget(target: Int) {
        bleManager.setMaxSpeed(target)
        speedLimiterTimeout?.cancel()
        speedLimiterTimeout = viewModelScope.launch {
            // The scooter answers in well under a second; if it doesn't, writeConfig re-reads the
            // real value and telemetry resyncs. This only guarantees the spinner can't stick.
            delay(SPEED_LIMITER_CONFIRM_TIMEOUT_MS)
            _pendingSpeedLimiter.value = null
        }
    }

    fun setStartingTorque(v: Int) = bleManager.setStartingTorque(v)
    fun setMaxDrivingTorque(v: Int) = bleManager.setMaxDrivingTorque(v)
    fun setBrakeStrength(v: Int) = bleManager.setBrakeStrength(v)

    // These three send a byte derived from the CURRENT state, so they must not fire before the
    // first status frame has told us what that state is.
    fun toggleCruise() {
        val on = telemetry.value.cruiseOn ?: return waitForStatus()
        bleManager.toggleCruise(on)
    }
    fun toggleHeadlight() {
        val on = telemetry.value.headlightOn ?: return waitForStatus()
        bleManager.toggleHeadlight(on)
    }
    fun toggleStartupMode() {
        val on = telemetry.value.startupModeOn ?: return waitForStatus()
        bleManager.toggleStartupMode(on)
    }
    private fun waitForStatus() = bleManager.postMessage("Waiting for scooter status...")

    fun setAmbientLampMode(mode: Int, color: com.devcat.escootercontrol.ble.ProtocolCodec.AmbientColor? = null) =
        bleManager.setAmbientLampMode(mode, color)

    fun pulseSeatLock() = bleManager.pulseSeatLock()
    fun pulseHelmetLock() = bleManager.pulseHelmetLock()
    fun pulseStorageLock() = bleManager.pulseStorageLock()
    fun pulseBrakeLock() = bleManager.pulseBrakeLock()

    override fun onCleared() {
        super.onCleared()
        bleManager.disconnect()
    }

    private companion object {
        const val SPEED_LIMITER_CONFIRM_TIMEOUT_MS = 3_000L
    }
}

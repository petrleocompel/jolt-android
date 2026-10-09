package cz.peelco.jolt.app

import cz.peelco.jolt.domain.model.DeviceConnectionState

/**
 * How the app is wired, the counterpart of iOS's launch arguments
 * (`-snapshotMode`, `-fakeDevice`, `-noDevice`, `-fakeDeviceLink`). UI tests
 * build a container with these directly; a normal launch uses [DEFAULT].
 */
data class AppEnvironment(
    /** In-memory server, no network: `-snapshotMode`. */
    val useMockBackend: Boolean = false,
    /** Stand-in wearable instead of Bluetooth: `-fakeDevice`. */
    val useFakeDevice: Boolean = false,
    /** The fake wearable starts unpaired: `-noDevice`. */
    val startsWithoutDevice: Boolean = false,
    /** Paired but unreachable: `-fakeDeviceLink offline|connecting|failed`. */
    val fakeDeviceLink: DeviceConnectionState? = null,
    /** The mock server starts signed in. */
    val startsSignedIn: Boolean = false,
    /** Settings and secrets kept in memory only, so runs are hermetic. */
    val inMemoryStorage: Boolean = false,
) {
    companion object {
        val DEFAULT = AppEnvironment()

        /** Everything faked, for UI tests and screenshots. */
        val SNAPSHOT = AppEnvironment(useMockBackend = true, useFakeDevice = true, startsSignedIn = true, inMemoryStorage = true)
    }
}

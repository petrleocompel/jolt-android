package cz.peelco.jolt.ui

import cz.peelco.jolt.app.AppEnvironment
import cz.peelco.jolt.app.JoltApplication

/** The app against the mock server, signed in, with a connected fake wearable. */
class SnapshotApplication : JoltApplication() {
    override fun environment() = AppEnvironment.SNAPSHOT
}

/** First run: no wearable has ever been paired. */
class NoDeviceApplication : JoltApplication() {
    override fun environment() = AppEnvironment.SNAPSHOT.copy(startsWithoutDevice = true)
}

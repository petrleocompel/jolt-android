package cz.peelco.jolt.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import cz.peelco.jolt.data.device.CompositeDeviceRepository
import kotlinx.coroutines.launch

open class JoltApplication : Application() {
    lateinit var container: AppContainer
        private set

    /** Tests override this to run the app against the mock server and fake device. */
    protected open fun environment(): AppEnvironment = AppEnvironment.DEFAULT

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        container = AppContainer(this, environment())
        container.start()
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    container.setForeground(true)
                    container.keepWearableConnected()
                    // A battery level that moved while backgrounded is
                    // corrected on the first frame.
                    (container.deviceRepository as? CompositeDeviceRepository)?.let { repository ->
                        container.scope.launch { repository.refreshDeviceInfoIfConnected() }
                    }
                }

                override fun onStop(owner: LifecycleOwner) {
                    container.setForeground(false)
                }
            },
        )
    }
}

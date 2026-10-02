package io.github.libxposed.service

import android.os.BadParcelableException
import android.os.Bundle
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.chimio.inxlocker.util.PrefsProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Uses the real libxposed RemotePreferences, including its asynchronous apply(). */
@RunWith(AndroidJUnit4::class)
class ForcedComponentPreferencesTest {
    private val key = PrefsProvider.KEY_FORCED_INSTALLER_COMPONENTS
    private val first = "installer.one/installer.one.InstallActivity"
    private val second = "installer.two/installer.two.InstallActivity"
    private val backend = FrameworkPreferences()

    @After
    fun releasePreferences() {
        PrefsProvider.release()
    }

    @Test
    fun unnormalizedKotlinEmptySetReproducesClassLoaderFailure() {
        val prefs = XposedService(backend).getRemotePreferences("regression")
        prefs.edit().putStringSet(key, emptySet()).commit()
        assertTrue(backend.nextUpdate().error is BadParcelableException)
    }

    @Test
    fun forcedComponentsSurviveToggleAndReload() {
        val prefs = XposedService(backend).getRemotePreferences("regression")
        PrefsProvider.init(prefs)
        var selected: Set<String> = emptySet()
        // Match MainActivity's long-press: empty -> singleton -> multiple -> singleton -> empty.
        for (component in listOf(first, second, first, second)) {
            selected = selected.toMutableSet().apply {
                if (!add(component)) remove(component)
            }.toSet()
            PrefsProvider.putStringSet(key, selected)
            assertEquals(selected, backend.nextUpdate().valueOrThrow())
            assertEquals(selected, PrefsProvider.forcedInstallerComponents.value)
            assertEquals(selected, prefs.getStringSet(key, null))
            val reloaded = XposedService(backend).getRemotePreferences("regression")
            assertEquals(selected, reloaded.getStringSet(key, null))
        }
    }

    @Test
    fun appDefinedAndMutableSetsAreCopiedBeforeApply() {
        val prefs = XposedService(backend).getRemotePreferences("regression")
        PrefsProvider.init(prefs)
        // A Serializable set from the APK is also unavailable to the framework.
        val appDefinedSet = AppDefinedSet().apply {
            add(first)
            add(second)
        }
        PrefsProvider.putStringSet(key, appDefinedSet)
        assertEquals(appDefinedSet, backend.nextUpdate().valueOrThrow())

        val mutable = mutableSetOf(first)
        PrefsProvider.putStringSet(key, mutable)
        assertNotSame(mutable, prefs.getStringSet(key, null))
        mutable.clear()
        assertEquals(setOf(first), backend.nextUpdate().valueOrThrow())
        assertEquals(setOf(first), PrefsProvider.getStringSet(key))
    }

    private class AppDefinedSet : HashSet<String>()

    private data class Update(val value: Set<String>? = null, val error: Throwable? = null) {
        fun valueOrThrow(): Set<String> {
            error?.let { throw it }
            return checkNotNull(value)
        }
    }

    /**
     * Stand-in for the framework process. Its Bundle is deserialized with Android's
     * boot class loader, which cannot resolve Kotlin/app classes or R8-renamed ones.
     * Failures are captured so the negative control cannot kill the test process.
     */
    private inner class FrameworkPreferences : IXposedService.Default() {
        private val values = HashMap<String, Any>()
        private val updates = LinkedBlockingQueue<Update>()

        override fun requestRemotePreferences(group: String): Bundle = Bundle().apply {
            putSerializable("map", HashMap(values))
        }

        @Suppress("DEPRECATION", "UNCHECKED_CAST")
        override fun updateRemotePreferences(group: String, diff: Bundle) {
            val parcel = Parcel.obtain()
            val update = try {
                diff.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                val received = Bundle.CREATOR.createFromParcel(parcel)
                received.classLoader = String::class.java.classLoader
                val put = received.getSerializable("put") as Map<String, Any>
                values.putAll(put)
                Update(value = HashSet(put[key] as Set<String>))
            } catch (error: Throwable) {
                Update(error = error)
            } finally {
                parcel.recycle()
            }
            updates.add(update)
        }

        fun nextUpdate(): Update =
            checkNotNull(updates.poll(10, TimeUnit.SECONDS)) {
                "RemotePreferences did not send its update"
            }
    }
}

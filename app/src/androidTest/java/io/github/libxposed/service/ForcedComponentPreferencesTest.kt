package io.github.libxposed.service

import android.os.BadParcelableException
import android.os.Bundle
import android.os.Parcel
import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.chimio.inxlocker.R
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
    @SdkSuppress(minSdkVersion = 36)
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

    @Test
    fun actualInstallerRowLongPressTogglesAndPersistsComponent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val testPackage = instrumentation.context.packageName
        val className = InstallerFixtureActivity::class.java.name
        val component = "$testPackage/$className"
        instrumentation.runOnMainSync {
            PrefsProvider.init(XposedService(backend).getRemotePreferences("ui-regression"))
        }
        val activity = instrumentation.startActivitySync(
            Intent().setClassName(context.packageName, "${context.packageName}.ui.activity.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            touchText(context.getString(R.string.installer_system_default))
            val label = "Regression APK installer"
            touchText(label, longPress = true)
            assertEquals(setOf(component), backend.nextUpdate().valueOrThrow())
            waitForText("$testPackage\n$className")

            touchText(label, longPress = true)
            assertEquals(emptySet<String>(), backend.nextUpdate().valueOrThrow())
            waitForText(testPackage)
            assertEquals(emptySet<String>(), PrefsProvider.forcedInstallerComponents.value)
            val reloaded = XposedService(backend).getRemotePreferences("ui-regression")
            assertEquals(emptySet<String>(), reloaded.getStringSet(key, null))
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun waitForText(text: String): Rect {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            val bounds = root?.let { findTextBounds(it, text) }
            @Suppress("DEPRECATION")
            root?.recycle()
            if (bounds != null && !bounds.isEmpty) return bounds
            SystemClock.sleep(100)
        }
        val root = automation.rootInActiveWindow
        val tree = root?.let(::describeTree)
        @Suppress("DEPRECATION")
        root?.recycle()
        throw AssertionError("Installer UI did not show: $text\n$tree")
    }

    // Compose exposes virtual children but does not implement the provider's
    // findAccessibilityNodeInfosByText. Walk the tree like a UI device driver.
    private fun findTextBounds(node: AccessibilityNodeInfo, text: String): Rect? {
        if (node.isVisibleToUser && node.text?.toString()?.contains(text) == true) {
            val bounds = Rect().also(node::getBoundsInScreen)
            if (!bounds.isEmpty) return bounds
        }
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            val bounds = findTextBounds(child, text)
            @Suppress("DEPRECATION")
            child.recycle()
            if (bounds != null) return bounds
        }
        return null
    }

    private fun describeTree(node: AccessibilityNodeInfo): String = buildString {
        appendLine("${node.className}: text=${node.text}, visible=${node.isVisibleToUser}")
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            append(describeTree(child))
            @Suppress("DEPRECATION")
            child.recycle()
        }
    }

    private fun touchText(text: String, longPress: Boolean = false) {
        val bounds = waitForText(text)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        val holdTime = if (longPress) ViewConfiguration.getLongPressTimeout() + 200L else 50L
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            if (action == MotionEvent.ACTION_UP) SystemClock.sleep(holdTime)
            val event = MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0
            )
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue("Failed to inject touch event", automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
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

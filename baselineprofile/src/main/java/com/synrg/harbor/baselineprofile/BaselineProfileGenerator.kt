package com.synrg.harbor.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Walks the paths that must feel instant: cold start, scrolling the home rows, opening a title,
 * and switching tabs. The collected profile ships in the app (src/release/generated).
 *
 * Gestures go by screen coordinates: Compose re-lays out often, so UiObject2 handles go stale.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "com.synrg.harbor", includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        device.waitForText("Tendencias de la semana")

        val w = device.displayWidth
        val h = device.displayHeight
        repeat(3) { device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 20) }
        device.swipe(w * 9 / 10, h / 2, w / 10, h / 2, 15)
        repeat(3) { device.swipe(w / 2, h / 4, w / 2, h * 3 / 4, 20) }
        device.waitForIdle()

        if (device.clickText("Detalles")) {
            device.waitForText("Agregar a mi lista")
            device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 20)
            device.swipe(w / 2, h / 4, w / 2, h * 3 / 4, 20)
            device.pressBack()
            device.waitForText("Tendencias de la semana")
        }

        for (tab in listOf("Continuar", "Buscar", "Mi lista", "Ajustes", "Inicio")) {
            device.clickText(tab)
            device.waitForIdle()
        }
    }

    private fun UiDevice.waitForText(text: String) {
        wait(Until.hasObject(By.text(text)), 15_000)
    }

    /** Taps the centre of the first node with [text]; false if it is not on screen. */
    private fun UiDevice.clickText(text: String): Boolean {
        val bounds = runCatching { findObject(By.text(text))?.visibleBounds }.getOrNull() ?: return false
        click(bounds.centerX(), bounds.centerY())
        return true
    }
}

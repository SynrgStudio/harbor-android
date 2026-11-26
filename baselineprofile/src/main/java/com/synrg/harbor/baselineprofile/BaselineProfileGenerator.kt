package com.synrg.harbor.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.regex.Pattern

private fun text(english: String, spanish: String): Pattern = Pattern.compile("${Pattern.quote(english)}|${Pattern.quote(spanish)}")

private val TRENDING = text("Trending this week", "Tendencias de la semana")
private val DETAILS = text("Details", "Detalles")
private val ADD_TO_LIST = text("Add to my list", "Agregar a mi lista")
private val TABS = listOf(
    text("Continue", "Continuar"),
    text("Search", "Buscar"),
    text("My list", "Mi lista"),
    text("Settings", "Ajustes"),
    text("Home", "Inicio"),
)

/**
 * Walks the paths that must feel instant: cold start, scrolling the home rows, opening a title,
 * and switching tabs. The collected profile ships in the app (src/release/generated).
 *
 * Gestures go by screen coordinates: Compose re-lays out often, so UiObject2 handles go stale.
 * Texts are matched in English or Spanish, whichever the device runs.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "com.synrg.harbor", includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        device.waitForText(TRENDING)

        val w = device.displayWidth
        val h = device.displayHeight
        repeat(3) { device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 20) }
        device.swipe(w * 9 / 10, h / 2, w / 10, h / 2, 15)
        repeat(3) { device.swipe(w / 2, h / 4, w / 2, h * 3 / 4, 20) }
        device.waitForIdle()

        if (device.clickText(DETAILS)) {
            device.waitForText(ADD_TO_LIST)
            device.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 20)
            device.swipe(w / 2, h / 4, w / 2, h * 3 / 4, 20)
            device.pressBack()
            device.waitForText(TRENDING)
        }

        for (tab in TABS) {
            device.clickText(tab)
            device.waitForIdle()
        }
    }

    private fun UiDevice.waitForText(text: Pattern) {
        wait(Until.hasObject(By.text(text)), 15_000)
    }

    /** Taps the centre of the first node with [text]; false if it is not on screen. */
    private fun UiDevice.clickText(text: Pattern): Boolean {
        val bounds = runCatching { findObject(By.text(text))?.visibleBounds }.getOrNull() ?: return false
        click(bounds.centerX(), bounds.centerY())
        return true
    }
}

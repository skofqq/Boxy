package com.skofqq.boxy.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Walks the first run and the tabs. The emulator needs no root or module: those parts show their empty states. */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = "com.skofqq.boxy") {
        pressHome()
        startActivityAndWait()
        device.finishOnboarding()
        listOf("Tools", "Settings", "Home").forEach { device.openTab(it) }
    }

    private fun UiDevice.finishOnboarding() {
        val width = displayWidth
        val height = displayHeight
        repeat(6) {
            // A tap on the right of a story moves on; stories also advance by themselves.
            if (findObject(By.textStartsWith("I have read")) == null) click(width * 3 / 4, height / 2)
            waitForIdle()
            Thread.sleep(500)
        }
        wait(Until.findObject(By.textStartsWith("I have read")), TIMEOUT)?.click()
        wait(Until.findObject(By.text("Next")), TIMEOUT)?.click()
        wait(Until.findObject(By.text("Get started")), TIMEOUT)?.click()
        wait(Until.hasObject(By.text("Tools")), TIMEOUT)
    }

    private fun UiDevice.openTab(label: String) {
        wait(Until.findObject(By.text(label)), TIMEOUT)?.click()
        Thread.sleep(1500)
        waitForIdle()
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}

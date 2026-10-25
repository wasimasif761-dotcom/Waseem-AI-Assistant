package com.waseemasif.aiagent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentTest {

    @Test
    fun testAppName() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Waseem AI", appName)
    }

    @Test
    fun testFallbackIntentParsingWifi() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val llmHelper = LLMHelper(context)
        val json = llmHelper.generateFallbackJson("Turn on Wi-Fi please")
        assertTrue(json.contains("SYSTEM_TOGGLE"))
        assertTrue(json.contains("WIFI"))
        assertTrue(json.contains("ON"))
    }

    @Test
    fun testFallbackIntentParsingNavigation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val llmHelper = LLMHelper(context)
        val json = llmHelper.generateFallbackJson("go home")
        assertTrue(json.contains("SYSTEM_NAV"))
        assertTrue(json.contains("HOME"))
    }

    @Test
    fun testActionExecutorParsing() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val executor = ActionExecutor(context)
        val testJson = """{"action": "SPEAK", "text": "Testing command", "explanation": "Test"}"""
        val result = executor.executeJson(testJson)
        assertTrue(result.isSuccess)
        assertNotNull(result.command)
        assertEquals("SPEAK", result.command?.action)
    }
}

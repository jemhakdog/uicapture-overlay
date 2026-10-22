package com.uicapture.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectScaffoldingTest {
    @Test
    fun testPackageIdentifier() {
        val packageName = "com.uicapture.app"
        assertEquals("com.uicapture.app", packageName)
    }

    @Test
    fun testBasicMath() {
        assertTrue(1 + 1 == 2)
    }
}

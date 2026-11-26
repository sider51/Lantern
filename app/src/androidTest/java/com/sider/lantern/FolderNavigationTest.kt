package com.sider.lantern

import androidx.navigation.NavHostController
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.composable
import androidx.navigation.createGraph
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FolderNavigationTest {
    @Test fun backReturnsFromChildToParentThenToLibrary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val nav = NavHostController(instrumentation.targetContext)
            nav.navigatorProvider.addNavigator(ComposeNavigator())
            nav.graph = nav.createGraph(startDestination = "library") {
                composable("library") {}
                composable("folder/{id}") {}
            }

            nav.openFolder("parent")
            nav.openFolder("child")
            nav.openFolder("child")
            assertEquals("child", nav.currentBackStackEntry?.arguments?.getString("id"))
            assertTrue(nav.popBackStack())
            assertEquals("parent", nav.currentBackStackEntry?.arguments?.getString("id"))
            assertTrue(nav.popBackStack())
            assertEquals("library", nav.currentDestination?.route)
        }
    }
}

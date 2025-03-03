/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.snackbar

import androidx.navigation.NavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.createTab
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.support.test.any
import mozilla.components.support.test.eq
import mozilla.components.support.test.libstate.ext.waitUntilIdle
import mozilla.components.support.test.mock
import mozilla.components.support.test.rule.MainCoroutineRule
import mozilla.components.support.test.rule.runTestOnMain
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mozilla.fenix.R
import org.mozilla.fenix.components.AppStore
import org.mozilla.fenix.components.FenixSnackbar
import org.mozilla.fenix.components.appstate.AppAction.BookmarkAction
import org.mozilla.fenix.components.appstate.AppAction.SnackbarAction
import org.mozilla.fenix.components.appstate.AppAction.TranslationsAction
import org.mozilla.fenix.components.appstate.snackbar.SnackbarState

@RunWith(AndroidJUnit4::class)
class SnackbarBindingTest {
    @get:Rule
    val coroutineRule = MainCoroutineRule()

    private lateinit var snackbarDelegate: FenixSnackbarDelegate
    private lateinit var navController: NavController

    @Before
    fun setUp() {
        snackbarDelegate = mock()
        navController = mock()
    }

    @Test
    fun `GIVEN translation is in progress for the current selected session WHEN snackbar state is updated to translation in progress THEN display the snackbar`() = runTestOnMain {
        val sessionId = "sessionId"
        val tab = createTab(url = "https://www.mozilla.org", id = sessionId)
        val browserStore = BrowserStore(
            BrowserState(
                tabs = listOf(tab),
                selectedTabId = sessionId,
            ),
        )
        val appStore = AppStore()

        val binding = SnackbarBinding(
            browserStore = browserStore,
            appStore = appStore,
            snackbarDelegate = snackbarDelegate,
            navController = navController,
            customTabSessionId = null,
        )

        binding.start()

        appStore.dispatch(
            TranslationsAction.TranslationStarted(sessionId = sessionId),
        )

        // Wait for TranslationsAction.TranslationStarted
        appStore.waitUntilIdle()
        // Wait for SnackbarAction.SnackbarShown
        appStore.waitUntilIdle()

        verify(snackbarDelegate).show(
            text = R.string.translation_in_progress_snackbar,
            duration = FenixSnackbar.LENGTH_INDEFINITE,
        )

        assertEquals(SnackbarState.None, appStore.state.snackbarState)
    }

    @Test
    fun `GIVEN translation is in progress for a different session WHEN snackbar state is updated to translation in progress THEN do not display the snackbar`() = runTestOnMain {
        val tab1 = createTab(url = "https://www.mozilla.org", id = "1")
        val tab2 = createTab(url = "https://www.mozilla.org", id = "2")
        val browserStore = BrowserStore(
            BrowserState(
                tabs = listOf(tab1, tab2),
                selectedTabId = tab1.id,
            ),
        )
        val appStore = AppStore()

        val binding = SnackbarBinding(
            browserStore = browserStore,
            appStore = appStore,
            snackbarDelegate = snackbarDelegate,
            navController = navController,
            customTabSessionId = null,
        )

        binding.start()

        appStore.dispatch(
            TranslationsAction.TranslationStarted(sessionId = tab2.id),
        )

        // Wait for TranslationsAction.TranslationStarted
        appStore.waitUntilIdle()
        // Wait for SnackbarAction.SnackbarShown
        appStore.waitUntilIdle()

        verify(snackbarDelegate, never()).show(
            text = R.string.translation_in_progress_snackbar,
            duration = FenixSnackbar.LENGTH_LONG,
        )
    }

    @Test
    fun `WHEN the snackbar state is updated to dismiss THEN dismiss the snackbar`() = runTestOnMain {
        val appStore = AppStore()
        val binding = SnackbarBinding(
            browserStore = mock(),
            appStore = appStore,
            snackbarDelegate = snackbarDelegate,
            navController = navController,
            customTabSessionId = null,
        )

        binding.start()

        appStore.dispatch(SnackbarAction.SnackbarDismissed)

        // Wait for SnackbarAction.SnackbarDismissed
        appStore.waitUntilIdle()
        // Wait for SnackbarAction.Reset
        appStore.waitUntilIdle()

        assertEquals(SnackbarState.None, appStore.state.snackbarState)
        verify(snackbarDelegate).dismiss()
    }

    @Test
    fun `GIVEN bookmark is added WHEN the bookmark added state action is dispatched THEN display the appropriate snackbar`() = runTestOnMain {
        val appStore = AppStore()
        val binding = SnackbarBinding(
            browserStore = mock(),
            appStore = appStore,
            snackbarDelegate = snackbarDelegate,
            navController = navController,
            customTabSessionId = null,
        )

        binding.start()

        appStore.dispatch(
            BookmarkAction.BookmarkAdded(guidToEdit = "1"),
        )

        // Wait for BookmarkAction.BookmarkAdded(guidToEdit = "1"),
        appStore.waitUntilIdle()
        // Wait for SnackbarAction.SnackbarShown
        appStore.waitUntilIdle()

        assertEquals(SnackbarState.None, appStore.state.snackbarState)

        verify(snackbarDelegate).show(
            text = eq(R.string.bookmark_saved_snackbar),
            duration = eq(FenixSnackbar.LENGTH_LONG),
            action = eq(R.string.edit_bookmark_snackbar_action),
            listener = any(),
        )
    }

    @Test
    fun `GIVEN no bookmark is added WHEN the bookmark added state action is dispatched THEN display the appropriate snackbar`() = runTestOnMain {
        val appStore = AppStore()
        val binding = SnackbarBinding(
            browserStore = mock(),
            appStore = appStore,
            snackbarDelegate = snackbarDelegate,
            navController = navController,
            customTabSessionId = null,
        )

        binding.start()

        appStore.dispatch(
            BookmarkAction.BookmarkAdded(guidToEdit = null),
        )

        // Wait for BookmarkAction.BookmarkAdded(guidToEdit = null),
        appStore.waitUntilIdle()
        // Wait for SnackbarAction.SnackbarShown
        appStore.waitUntilIdle()

        assertEquals(SnackbarState.None, appStore.state.snackbarState)
        verify(snackbarDelegate).show(
            text = R.string.bookmark_invalid_url_error,
            duration = FenixSnackbar.LENGTH_LONG,
        )
    }
}

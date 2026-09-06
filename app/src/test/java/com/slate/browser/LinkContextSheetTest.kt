package com.slate.browser

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import com.slate.browser.data.ThemeMode
import com.slate.browser.ui.components.LinkContextActions
import com.slate.browser.ui.components.LinkContextSheet
import com.slate.browser.ui.theme.SlateTheme
import com.slate.browser.web.LinkContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The long-press sheet offers what applies to the element and nothing else. Offering "Save
 * image" for a text link, or "Open in new tab" for a bare image, is the kind of clutter that
 * makes a context menu useless.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp")
class LinkContextSheetTest {

    @get:Rule val compose = createComposeRule()

    private var opened = 0
    private var openedInBackground = 0
    private var savedImage = 0

    @OptIn(ExperimentalMaterial3Api::class)
    private fun show(context: LinkContext) {
        compose.setContent {
            SlateTheme(themeMode = ThemeMode.LIGHT) {
                LinkContextSheet(
                    context = context,
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    onDismiss = {},
                    actions = LinkContextActions(
                        onOpen = { opened++ },
                        onOpenInNewTab = { openedInBackground++ },
                        onOpenInNewTabAndSwitch = {},
                        onCopyLink = {},
                        onShareLink = {},
                        onOpenImage = {},
                        onSaveImage = { savedImage++ },
                        onCopyImageAddress = {},
                        onShareImage = {},
                    ),
                )
            }
        }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
    }

    @Test
    fun `a plain link offers link actions and no image actions`() {
        show(LinkContext(linkUrl = "https://example.com/article"))

        compose.onNodeWithText("Open in new tab").assertIsDisplayed()
        compose.onNodeWithText("Open and switch").assertIsDisplayed()
        compose.onNodeWithText("Copy link").assertIsDisplayed()
        compose.onNodeWithText("Share link").assertIsDisplayed()

        compose.onNodeWithText("Save image").assertDoesNotExist()
        compose.onNodeWithText("Copy image address").assertDoesNotExist()
    }

    @Test
    fun `a plain image offers image actions and no link actions`() {
        show(LinkContext(imageUrl = "https://cdn.example.com/photo.jpg"))

        compose.onNodeWithText("Save image").assertIsDisplayed()
        compose.onNodeWithText("Open image").assertIsDisplayed()
        compose.onNodeWithText("Copy image address").assertIsDisplayed()

        compose.onNodeWithText("Open in new tab").assertDoesNotExist()
        compose.onNodeWithText("Copy link").assertDoesNotExist()
    }

    @Test
    fun `an image inside a link offers both, kept apart`() {
        show(
            LinkContext(
                linkUrl = "https://example.com/product",
                imageUrl = "https://cdn.example.com/product.jpg",
            )
        )

        compose.onNodeWithText("Open in new tab").assertIsDisplayed()
        compose.onNodeWithText("Copy link").assertIsDisplayed()
        compose.onNodeWithText("Save image").assertIsDisplayed()
        compose.onNodeWithText("Copy image address").assertIsDisplayed()
    }

    @Test
    fun `the sheet says what it is about`() {
        show(LinkContext(linkUrl = "https://example.com/a/very/specific/page"))
        compose.onNodeWithText("https://example.com/a/very/specific/page").assertIsDisplayed()
    }

    @Test
    fun `actions do what they say`() {
        show(LinkContext(linkUrl = "https://example.com", imageUrl = "https://example.com/i.png"))

        // Touch injection does not route into a bottom sheet's own window under Robolectric,
        // so the row's click action is invoked directly. Same contract, reachable harness.
        compose.onNodeWithText("Open").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(1, opened)

        compose.onNodeWithText("Save image").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(1, savedImage)

        // "Open in new tab" says nothing about going there, and does not.
        compose.onNodeWithText("Open in new tab").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(1, openedInBackground)
    }

    @Test
    fun `nothing actionable means nothing is offered`() {
        // Plain text, form fields and the like never reach the sheet; the page keeps them so
        // selection handles and the platform text menu behave normally.
        assertFalse(LinkContext().isActionable)
        assertFalse(LinkContext(linkUrl = "").isActionable)
        assertTrue(LinkContext(imageUrl = "https://x.test/a.png").isActionable)
    }
}

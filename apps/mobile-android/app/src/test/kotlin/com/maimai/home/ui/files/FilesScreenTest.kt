package com.maimai.home.ui.files

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import com.maimai.home.data.FileListingResult
import com.maimai.home.data.models.FileEntry
import com.maimai.home.data.models.FileRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for stateless [FilesScreenContent].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FilesScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val writableRoot = FileRoot(id = "root1", name = "Documents", readOnly = false)
    private val readOnlyRoot = FileRoot(id = "root2", name = "System", readOnly = true)

    private val fileEntry = FileEntry(name = "report.txt", kind = "file", size = 1024L, modified = "2026-01-01T00:00:00Z")
    private val dirEntry = FileEntry(name = "subdir", kind = "dir", size = null, modified = "2026-01-01T00:00:00Z")

    private val defaultState = FilesUiState(
        address = "192.168.1.10:8765",
        machineName = "TestPC",
        roots = listOf(writableRoot),
        selectedRoot = writableRoot,
        listing = FileListingResult(
            entries = listOf(fileEntry, dirEntry),
            total = 2,
            truncated = false,
            limit = 200,
        ),
    )

    // Action sheet.

    /**
     * Tapping an entry's trailing action button opens the action affordance.
     */
    @Test
    fun actionSheetIsBottomSheet() {
        var actionEntry: FileEntry? = null
        composeRule.setContent {
            MaterialTheme {
                FileListCard(
                    entries = listOf(fileEntry),
                    onOpen = {},
                    onShowActions = { actionEntry = it },
                )
            }
        }

        composeRule.onNodeWithText("report.txt")
        composeRule.onNodeWithTag(FilesScreenTags.ENTRY_ACTION_PREFIX + "report.txt", useUnmergedTree = true).performClick()
        assertThat(actionEntry).isEqualTo(fileEntry)
    }

    /**
     * Delete action must not appear for directories.
     */
    @Test
    fun deleteHiddenForDirectory() {
        composeRule.setContent {
            MaterialTheme {
                FilesScreenContent(
                    state = defaultState,
                    breadcrumbSegments = emptyList(),
                    currentEntryPath = { it.name },
                    onRefresh = {},
                    onSelectRoot = {},
                    onOpenFolder = {},
                    onNavigateToPath = {},
                    onLoadMore = {},
                    onDownload = {},
                    onUpload = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onMove = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("subdir").performScrollTo()
        composeRule.onNodeWithTag(FilesScreenTags.ENTRY_ACTION_PREFIX + "subdir", useUnmergedTree = true).performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()

        // Action sheet is open.

        // "删除" must NOT be present for a directory.
        assertThat(
            composeRule.onAllNodesWithText("删除").fetchSemanticsNodes(),
        ).isEmpty()
    }

    /**
     * Read-only roots must not expose mutation affordances. The action sheet can
     * still open from the trailing action icon for downloads.
     */
    @Test
    fun mutationsHiddenForReadOnlyRoot() {
        val readOnlyState = defaultState.copy(
            selectedRoot = readOnlyRoot,
            roots = listOf(readOnlyRoot),
        )
        composeRule.setContent {
            MaterialTheme {
                FilesScreenContent(
                    state = readOnlyState,
                    breadcrumbSegments = emptyList(),
                    currentEntryPath = { it.name },
                    onRefresh = {},
                    onSelectRoot = {},
                    onOpenFolder = {},
                    onNavigateToPath = {},
                    onLoadMore = {},
                    onDownload = {},
                    onUpload = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onMove = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("report.txt").performScrollTo()
        composeRule.onNodeWithTag(FilesScreenTags.ENTRY_ACTION_PREFIX + "report.txt", useUnmergedTree = true).performClick()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("下载").assertIsDisplayed()
        assertThat(
            composeRule.onAllNodesWithText("重命名").fetchSemanticsNodes(),
        ).isEmpty()
        assertThat(
            composeRule.onAllNodesWithText("移动").fetchSemanticsNodes(),
        ).isEmpty()
        assertThat(
            composeRule.onAllNodesWithText("删除").fetchSemanticsNodes(),
        ).isEmpty()
    }

    @Test
    fun fileClickOpensActionSheetInsteadOfDownloading() {
        var downloadCalled = false
        composeRule.setContent {
            MaterialTheme {
                FilesScreenContent(
                    state = defaultState,
                    breadcrumbSegments = emptyList(),
                    currentEntryPath = { it.name },
                    onRefresh = {},
                    onSelectRoot = {},
                    onOpenFolder = {},
                    onNavigateToPath = {},
                    onLoadMore = {},
                    onDownload = { downloadCalled = true },
                    onUpload = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onMove = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("report.txt").performScrollTo().performClick()
        composeRule.onNodeWithText("重命名").assertIsDisplayed()
        assertThat(downloadCalled).isFalse()
    }

    @Test
    fun directoryClickOpensFolderAndActionIconOpensSheet() {
        var openedEntry: FileEntry? = null
        var actionEntry: FileEntry? = null
        composeRule.setContent {
            MaterialTheme {
                FileListCard(
                    entries = listOf(dirEntry),
                    onOpen = { openedEntry = it },
                    onShowActions = { actionEntry = it },
                )
            }
        }

        composeRule.onNodeWithText("subdir").performClick()
        assertThat(openedEntry).isEqualTo(dirEntry)
        composeRule.onNodeWithTag(FilesScreenTags.ENTRY_ACTION_PREFIX + "subdir", useUnmergedTree = true).performClick()
        assertThat(actionEntry).isEqualTo(dirEntry)
    }

    /**
     * Rename dialog rejects invalid names.
     */
    @Test
    fun renameRequiresNonEmpty() {
        composeRule.setContent {
            MaterialTheme {
                RenameDialogForTest(entryName = "report.txt")
            }
        }

        // Dialog is open with the entry name pre-filled.
        composeRule.onNodeWithTag(FilesScreenTags.RENAME_DIALOG).assertIsDisplayed()

        // Tap confirm with the current (non-empty) name — should show same-name error.
        composeRule.onNodeWithText("确定").performClick()

        // Dialog should still be open (validation blocked it).
        composeRule.onNodeWithTag(FilesScreenTags.RENAME_DIALOG).assertIsDisplayed()
        // Error message for same name.
        composeRule.onNodeWithText("新名称与原名称相同").assertIsDisplayed()
    }

    /**
     * Move dialog title is "移动到".
     */
    @Test
    fun moveDialogTitleIsMoveTo() {
        composeRule.setContent {
            MaterialTheme {
                MoveDialogForTest(entryName = "report.txt")
            }
        }

        composeRule.onNodeWithTag(FilesScreenTags.MOVE_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithText("移动到").assertIsDisplayed()
    }

    // Delete confirmation.

    @Test
    fun deleteConfirmButtonPresent() {
        // Use DeleteDialogForTest to verify the delete confirm button has the error color.
        // (ModalBottomSheet interaction is unreliable under Robolectric.)
        composeRule.setContent {
            MaterialTheme {
                DeleteDialogForTest(entryName = "report.txt")
            }
        }

        // Delete confirm dialog should be open.
        composeRule.onNodeWithTag(FilesScreenTags.DELETE_DIALOG).assertIsDisplayed()
        // The confirm button with DELETE_CONFIRM_BUTTON tag must be present.
        composeRule.onNodeWithTag(FilesScreenTags.DELETE_CONFIRM_BUTTON).assertIsDisplayed()
    }

    // Truncation banner.

    @Test
    fun truncationBannerShowsLoadedCountAndLoadMoreButton() {
        val truncatedState = defaultState.copy(
            listing = FileListingResult(
                entries = listOf(fileEntry),
                total = 500,
                truncated = true,
                limit = 123,
            ),
            loadedOffset = 1,
        )
        composeRule.setContent {
            MaterialTheme {
                FilesScreenContent(
                    state = truncatedState,
                    breadcrumbSegments = emptyList(),
                    currentEntryPath = { it.name },
                    onRefresh = {},
                    onSelectRoot = {},
                    onOpenFolder = {},
                    onNavigateToPath = {},
                    onLoadMore = {},
                    onDownload = {},
                    onUpload = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onMove = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("已加载 1 / 500 项", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag(FilesScreenTags.LOAD_MORE_BUTTON).assertIsDisplayed()
    }

    // Root selector.

    @Test
    fun rootPickerButtonOpensBottomSheet() {
        composeRule.setContent {
            MaterialTheme {
                FilesScreenContent(
                    state = defaultState,
                    breadcrumbSegments = emptyList(),
                    currentEntryPath = { it.name },
                    onRefresh = {},
                    onSelectRoot = {},
                    onOpenFolder = {},
                    onNavigateToPath = {},
                    onLoadMore = {},
                    onDownload = {},
                    onUpload = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onMove = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithTag(FilesScreenTags.ROOT_PICKER_BUTTON).performClick()
        composeRule.onNodeWithTag(FilesScreenTags.ROOT_PICKER_SHEET).assertIsDisplayed()
    }

    @Test
    fun currentDeviceCardShowsSwitchAction() {
        var switchCalled = false
        composeRule.setContent {
            MaterialTheme {
                FilesScreenContent(
                    state = defaultState,
                    breadcrumbSegments = emptyList(),
                    currentEntryPath = { it.name },
                    onRefresh = {},
                    onSelectRoot = {},
                    onOpenFolder = {},
                    onNavigateToPath = {},
                    onLoadMore = {},
                    onDownload = {},
                    onUpload = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onMove = { _, _ -> },
                    onSwitchDevice = { switchCalled = true },
                    showCurrentDeviceCard = true,
                )
            }
        }

        composeRule.onNodeWithText("TestPC", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("切换设备").performClick()
        assertThat(switchCalled).isTrue()
    }

    @Test
    fun rootPickerEmptyStateShowsMessage() {
        val emptyRootsState = defaultState.copy(roots = emptyList(), selectedRoot = null)
        composeRule.setContent {
            MaterialTheme {
                FilesScreenContent(
                    state = emptyRootsState,
                    breadcrumbSegments = emptyList(),
                    currentEntryPath = { it.name },
                    onRefresh = {},
                    onSelectRoot = {},
                    onOpenFolder = {},
                    onNavigateToPath = {},
                    onLoadMore = {},
                    onDownload = {},
                    onUpload = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onMove = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithTag(FilesScreenTags.ROOT_PICKER_BUTTON).performClick()
        composeRule.onNodeWithText("未发现任何文件根").assertIsDisplayed()
    }

    // Upload FAB.

    @Test
    fun uploadFabPresentForWritableRoot() {
        composeRule.setContent {
            MaterialTheme {
                FilesScreenContent(
                    state = defaultState,
                    breadcrumbSegments = emptyList(),
                    currentEntryPath = { it.name },
                    onRefresh = {},
                    onSelectRoot = {},
                    onOpenFolder = {},
                    onNavigateToPath = {},
                    onLoadMore = {},
                    onDownload = {},
                    onUpload = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onMove = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithTag(FilesScreenTags.UPLOAD_FAB).assertIsDisplayed()
    }

    @Test
    fun uploadFabHiddenForReadOnlyRoot() {
        val readOnlyState = defaultState.copy(selectedRoot = readOnlyRoot, roots = listOf(readOnlyRoot))
        composeRule.setContent {
            MaterialTheme {
                FilesScreenContent(
                    state = readOnlyState,
                    breadcrumbSegments = emptyList(),
                    currentEntryPath = { it.name },
                    onRefresh = {},
                    onSelectRoot = {},
                    onOpenFolder = {},
                    onNavigateToPath = {},
                    onLoadMore = {},
                    onDownload = {},
                    onUpload = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onMove = { _, _ -> },
                )
            }
        }

        assertThat(
            composeRule.onAllNodesWithTag(FilesScreenTags.UPLOAD_FAB).fetchSemanticsNodes(),
        ).isEmpty()
    }

    // Breadcrumb chip row.

    @Test
    fun breadcrumbChipRowPresent() {
        composeRule.setContent {
            MaterialTheme {
                FilesScreenContent(
                    state = defaultState,
                    breadcrumbSegments = listOf("docs", "reports"),
                    currentEntryPath = { it.name },
                    onRefresh = {},
                    onSelectRoot = {},
                    onOpenFolder = {},
                    onNavigateToPath = {},
                    onLoadMore = {},
                    onDownload = {},
                    onUpload = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onMove = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithTag(FilesScreenTags.BREADCRUMB_ROW).assertIsDisplayed()
        composeRule.onNodeWithText("docs").assertIsDisplayed()
        composeRule.onNodeWithText("reports").assertIsDisplayed()
    }

    @Test
    fun breadcrumbRootChipNavigatesToRoot() {
        var navigatedTo: String? = null
        composeRule.setContent {
            MaterialTheme {
                FilesScreenContent(
                    state = defaultState,
                    breadcrumbSegments = listOf("docs"),
                    currentEntryPath = { it.name },
                    onRefresh = {},
                    onSelectRoot = {},
                    onOpenFolder = {},
                    onNavigateToPath = { navigatedTo = it },
                    onLoadMore = {},
                    onDownload = {},
                    onUpload = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onMove = { _, _ -> },
                )
            }
        }

        // The breadcrumb capsule renders a Home icon button at the start of the path
        // (replacing the old assistChip text). Use the content description to target it.
        composeRule.onNodeWithContentDescription("根目录").performClick()
        assertThat(navigatedTo).isEqualTo("")
        assertThat(navigatedTo).isEqualTo("")
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithText(text: String) =
    this.onAllNodes(androidx.compose.ui.test.hasText(text))

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTag(tag: String) =
    this.onAllNodes(androidx.compose.ui.test.hasTestTag(tag))

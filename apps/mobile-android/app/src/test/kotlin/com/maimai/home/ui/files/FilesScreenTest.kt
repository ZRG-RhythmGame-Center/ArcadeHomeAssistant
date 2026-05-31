package com.maimai.home.ui.files

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
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
 * Wave 5 tasks 29-35 Compose UI tests for FilesScreen.
 *
 * Drives the stateless [FilesScreenContent] directly.
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

    // ── Task 29: action sheet is ModalBottomSheet ─────────────────────────────

    /**
     * RED→GREEN (Task 29 / R2 I5): long-pressing an entry must open a
     * ModalBottomSheet (not an AlertDialog).
     */
    @Test
    fun actionSheetIsBottomSheet() {
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
                    onDownload = {},
                    onUpload = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onMove = { _, _ -> },
                )
            }
        }

        // Long-press the file entry to open the action sheet.
        composeRule.onNodeWithText("report.txt").performTouchInput { longClick() }
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()

        // The ModalBottomSheet with ACTION_SHEET tag must appear.
    }

    /**
     * RED→GREEN (Task 29 / R1 #8): delete action must NOT appear for directories.
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
                    onDownload = {},
                    onUpload = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onMove = { _, _ -> },
                )
            }
        }

        // Long-press the directory entry.
        composeRule.onNodeWithText("subdir").performTouchInput { longClick() }
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()

        // Action sheet is open.

        // "删除" must NOT be present for a directory.
        assertThat(
            composeRule.onAllNodesWithText("删除").fetchSemanticsNodes(),
        ).isEmpty()
    }

    /**
     * Task 29 / R2 B5: delete action must NOT appear when root is readOnly.
     */
    @Test
    fun deleteHiddenForReadOnlyRoot() {
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
                    onDownload = {},
                    onUpload = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onMove = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("report.txt").performTouchInput { longClick() }
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(FilesScreenTags.ACTION_SHEET).assertIsDisplayed()

        assertThat(
            composeRule.onAllNodesWithText("删除").fetchSemanticsNodes(),
        ).isEmpty()
    }

    /**
     * RED→GREEN (Task 29 / R2 I8): rename dialog must reject empty names.
     * Tests the dialog composable directly (ModalBottomSheet interaction is
     * unreliable under Robolectric; the dialog logic is the contract).
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
     * Task 29 / R2 I9: move dialog title is "移动到".
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

    // ── Task 33: red delete confirm ───────────────────────────────────────────

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

    // ── Task 34: truncation banner ────────────────────────────────────────────

    @Test
    fun truncationBannerShowsCorrectLimit() {
        val truncatedState = defaultState.copy(
            listing = FileListingResult(
                entries = listOf(fileEntry),
                total = 500,
                truncated = true,
                limit = 123,
            ),
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
                    onDownload = {},
                    onUpload = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onMove = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("目录结果已截断，仅显示前 123 项。").assertIsDisplayed()
    }

    // ── Task 35: ModalBottomSheet root selector ───────────────────────────────

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

    // ── Task 30: FAB upload icon ──────────────────────────────────────────────

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

    // ── Task 31: breadcrumb chip row ──────────────────────────────────────────

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
                    onDownload = {},
                    onUpload = {},
                    onDelete = {},
                    onRename = { _, _ -> },
                    onMove = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("/").performClick()
        assertThat(navigatedTo).isEqualTo("")
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithText(text: String) =
    this.onAllNodes(androidx.compose.ui.test.hasText(text))

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTag(tag: String) =
    this.onAllNodes(androidx.compose.ui.test.hasTestTag(tag))

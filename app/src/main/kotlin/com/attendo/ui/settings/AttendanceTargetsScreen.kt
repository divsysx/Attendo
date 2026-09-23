package com.attendo.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.attendo.core.model.Percent
import com.attendo.ui.components.AttendoTopBar
import com.attendo.ui.components.ConfirmDialog
import com.attendo.ui.components.LoadingPane
import com.attendo.ui.components.PercentPicker
import com.attendo.ui.components.SectionLabel
import com.attendo.ui.courses

/**
 * The three target decisions, on one screen, and nothing else.
 *
 * They are three because they answer three different questions, and the app is only honest
 * if it keeps them apart:
 *
 * - **the overall target** is the threshold the dashboard's single figure is judged against;
 * - **the default for new courses** is what a course added from here on starts at;
 * - **the bulk apply** is the one way a single percentage reaches courses that already exist.
 *
 * The order matters. A student who moves the default and expects their existing courses to
 * follow has misunderstood the screen, so the middle section says in as many words that it
 * does not, and the third is the section that does.
 */
@Composable
fun AttendanceTargetsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirming by remember { mutableStateOf(false) }

    // What the bulk control is currently showing. Seeded from the default so the common
    // case — "everything should be on the same target as new courses" — needs no dragging.
    // Held as basis points because a Percent is a value class and rememberSaveable needs
    // something the bundle already knows how to write.
    var bulkBasisPoints by rememberSaveable {
        mutableIntStateOf(state.settings.courseTarget.basisPoints)
    }
    val bulk = Percent.ofBasisPoints(bulkBasisPoints)

    Column(Modifier.fillMaxSize()) {
        AttendoTopBar(title = "Attendance Targets", onBack = onBack)

        if (!state.loaded) {
            LoadingPane()
            return@Column
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionLabel("Overall target") }
            item {
                Column {
                    Text(
                        text = "The threshold the figure on your Attendance tab is judged " +
                            "against. It does not change any course's own target.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    PercentPicker(
                        value = state.settings.overallTarget,
                        onSelect = viewModel::setOverallTarget,
                    )
                }
            }

            item { HorizontalDivider(Modifier.padding(top = 4.dp)) }

            item { SectionLabel("Default for new courses") }
            item {
                Column {
                    Text(
                        text = "What a course starts at when you add it. Changing this does " +
                            "not touch the courses you already have — they keep the target " +
                            "they were given, which is the point of setting one per course.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    PercentPicker(
                        value = state.settings.courseTarget,
                        onSelect = viewModel::setCourseTarget,
                    )
                }
            }

            item { HorizontalDivider(Modifier.padding(top = 4.dp)) }

            item { SectionLabel("Apply to every course") }
            item {
                Column {
                    Text(
                        text = if (state.courseCount == 0) {
                            "You are not tracking any courses yet, so there is nothing to " +
                                "apply a target to."
                        } else {
                            "Sets one target on all ${courses(state.courseCount)} you are " +
                                "tracking at once. Archived courses are left exactly as they " +
                                "are — a finished course's target is part of a closed record."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    PercentPicker(
                        value = bulk,
                        onSelect = { chosen -> bulkBasisPoints = chosen.basisPoints },
                        enabled = state.courseCount > 0,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { confirming = true },
                        enabled = state.courseCount > 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (state.courseCount == 1) {
                                "Apply to 1 active course"
                            } else {
                                "Apply to ${state.courseCount} active courses"
                            },
                        )
                    }
                }
            }
        }
    }

    if (confirming) {
        // The count is read again here rather than carried from the button, so the sentence
        // the student agrees to and the number the repository will write are the same read.
        ConfirmDialog(
            title = "Apply ${bulk.format(0)}% to every active course?",
            body = "This sets ${bulk.format(0)}% on all ${courses(state.courseCount)} you are " +
                "tracking. Archived courses are not changed. You can set any course back to " +
                "its own target afterwards from that course's screen.",
            confirmLabel = "Apply",
            destructive = false,
            onConfirm = { viewModel.applyTargetToAllActiveCourses(bulk) },
            onDismiss = { confirming = false },
        )
    }
}

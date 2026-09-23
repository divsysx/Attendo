package com.attendo.ui

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.attendo.ui.attendance.CourseDetailScreen
import com.attendo.ui.attendance.CourseEditorScreen
import com.attendo.ui.attendance.CoursesScreen
import com.attendo.ui.attendance.DashboardScreen
import com.attendo.ui.attendance.DayReviewScreen
import com.attendo.ui.attendance.SeedScreen
import com.attendo.ui.community.CommunityViewModel
import com.attendo.ui.community.MyCommunityScreen
import com.attendo.ui.rooms.RoomDetailScreen
import com.attendo.ui.rooms.RoomsScreen
import com.attendo.ui.rooms.RoomsViewModel
import com.attendo.ui.settings.AccountSettingsScreen
import com.attendo.ui.settings.AttendanceTargetsScreen
import com.attendo.ui.settings.BackupScreen
import com.attendo.ui.settings.HolidaysScreen
import com.attendo.ui.settings.RemovedAccountNotice
import com.attendo.ui.settings.RemovedAccountNoticeViewModel
import com.attendo.ui.settings.ReportingIdentityScreen
import com.attendo.ui.settings.SettingsScreen
import com.attendo.ui.settings.WorkingSaturdaysScreen
import com.attendo.ui.rollover.RolloverGate
import com.attendo.ui.rollover.RolloverViewModel
import java.time.LocalDate

/**
 * The whole app: two tabs, and everything else pushed on top of them.
 *
 * There is exactly one [Scaffold] here, and it owns the bottom bar and the window insets.
 * Screens are plain columns starting with an [com.attendo.ui.components.AttendoTopBar] —
 * nesting a second Scaffold per screen is the usual way Compose apps end up padding the
 * status bar twice.
 */
@Composable
fun AttendoApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    // Hoisted to the activity's own ViewModelStore rather than the Rooms destination's.
    // The bottom-bar pop that switches tabs drops the destination's store on the way out, and a
    // fresh RoomsViewModel would rebuild its StateFlow from the initial loaded=false value —
    // so every switch back to the Rooms tab flashed the LoadingPane while the timetable parsed
    // and the availability flow re-collected. Kept here, the instance (and its collected state)
    // survives the pop, and the tab reads as already loaded.
    val roomsViewModel: RoomsViewModel = viewModel(factory = RoomsViewModel.Factory)
    // Hoisted at the top level for the same reason roomsViewModel is: one community
    // state and one Realtime channel shared by every community surface, alive across
    // all navigation. It mints nothing on its own — the first community screen's
    // onShown() starts the engine, preserving D1-A (identity on first community view,
    // never on app open).
    val communityViewModel: CommunityViewModel = viewModel(factory = CommunityViewModel.Factory)
    // Hoisted at the top level for the same reason roomsViewModel is: the gate must survive the
    // back-stack changes underneath it, and its detection flow must keep running regardless of
    // which destination is current. A gate that lived in a destination's composition would be torn
    // down on every navigation, re-running detection and flickering the overlay.
    val rolloverViewModel: RolloverViewModel = viewModel(factory = RolloverViewModel.Factory)
    // Hoisted here for the same reason, and for one more: the deletion is established by a
    // background validation with no screen open, so the sentence about it has to belong to
    // the app rather than to a destination. See RemovedAccountNotice.
    val removedAccountNoticeViewModel: RemovedAccountNoticeViewModel =
        viewModel(factory = RemovedAccountNoticeViewModel.Factory)

    // The gate overlays the whole nav graph in a Box so it can cover the Scaffold when a rollover
    // is required, without becoming a route of its own. Rendering nothing while Idle keeps the
    // nav graph fully interactive the rest of the time.
    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                if (route == Routes.DASHBOARD || route == Routes.ROOMS) {
                    AttendoNavBar(
                        current = route,
                        onSelect = { destination -> navController.switchTab(destination) },
                    )
                }
            },
        ) { insets ->
            NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier
                .fillMaxSize()
                .padding(insets),
            // Navigation Compose's stock transition is a 700 ms crossfade — long enough
            // that both screens compose together for most of a second on a cold
            // destination, which reads as a stall. A 180 ms fade hands over in about ten
            // frames: still a visible transition, but the incoming screen is on its own
            // quickly enough to stay smooth.
            enterTransition = { fadeIn(tween(180)) },
            exitTransition = { fadeOut(tween(180)) },
            popEnterTransition = { fadeIn(tween(180)) },
            popExitTransition = { fadeOut(tween(180)) },
        ) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onOpenDay = { date -> navController.navigate(Routes.day(date)) },
                    onOpenCourse = { id -> navController.navigate(Routes.course(id)) },
                    onOpenCourses = { navController.navigate(Routes.COURSES) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    onSeed = { navController.navigate(Routes.SEED) },
                )
            }

            composable(
                route = Routes.DAY,
                arguments = listOf(navArgument(Routes.Arg.DATE) { type = NavType.StringType }),
            ) { entry ->
                val date = entry.arguments
                    ?.getString(Routes.Arg.DATE)
                    ?.let(LocalDate::parse)
                    ?: LocalDate.now()
                DayReviewScreen(
                    date = date,
                    onBack = navController::popBackStack,
                )
            }

            composable(Routes.COURSES) {
                CoursesScreen(
                    onBack = navController::popBackStack,
                    onOpenCourse = { id -> navController.navigate(Routes.course(id)) },
                    onEditCourse = { id -> navController.navigate(Routes.courseEditor(id)) },
                    onAddCourse = { navController.navigate(Routes.courseEditor()) },
                    onSeed = { navController.navigate(Routes.SEED) },
                )
            }

            composable(
                route = Routes.COURSE,
                arguments = listOf(navArgument(Routes.Arg.COURSE_ID) { type = NavType.LongType }),
            ) { entry ->
                CourseDetailScreen(
                    courseId = entry.courseId(),
                    onBack = navController::popBackStack,
                    onEdit = { id -> navController.navigate(Routes.courseEditor(id)) },
                    onOpenDay = { date -> navController.navigate(Routes.day(date)) },
                )
            }

            composable(
                route = Routes.COURSE_EDITOR,
                arguments = listOf(navArgument(Routes.Arg.COURSE_ID) { type = NavType.LongType }),
            ) { entry ->
                CourseEditorScreen(
                    courseId = entry.courseId(),
                    onDone = navController::popBackStack,
                )
            }

            composable(Routes.SEED) {
                SeedScreen(
                    onBack = navController::popBackStack,
                    onDone = navController::popBackStack,
                )
            }

            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = navController::popBackStack,
                    onSeed = { navController.navigate(Routes.SEED) },
                    onOpenBackup = { navController.navigate(Routes.BACKUP) },
                    onOpenAccount = { navController.navigate(Routes.ACCOUNT) },
                    onOpenMyCommunity = { navController.navigate(Routes.MY_COMMUNITY) },
                    onOpenReportingIdentity = { navController.navigate(Routes.REPORTING_IDENTITY) },
                    onOpenTargets = { navController.navigate(Routes.TARGETS) },
                    onOpenHolidays = { navController.navigate(Routes.HOLIDAYS) },
                    onOpenWorkingSaturdays = { navController.navigate(Routes.WORKING_SATURDAYS) },
                )
            }

            composable(Routes.TARGETS) {
                AttendanceTargetsScreen(onBack = navController::popBackStack)
            }

            composable(Routes.HOLIDAYS) {
                HolidaysScreen(onBack = navController::popBackStack)
            }

            composable(Routes.WORKING_SATURDAYS) {
                WorkingSaturdaysScreen(onBack = navController::popBackStack)
            }

            composable(Routes.BACKUP) {
                BackupScreen(onBack = navController::popBackStack)
            }

            composable(Routes.ACCOUNT) {
                AccountSettingsScreen(onBack = navController::popBackStack)
            }

            composable(Routes.REPORTING_IDENTITY) {
                ReportingIdentityScreen(onBack = navController::popBackStack)
            }

            composable(Routes.MY_COMMUNITY) {
                MyCommunityScreen(
                    onBack = navController::popBackStack,
                    communityViewModel = communityViewModel,
                )
            }

            composable(Routes.ROOMS) {
                RoomsScreen(
                    onOpenRoom = { room -> navController.navigate(Routes.room(room)) },
                    onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                    viewModel = roomsViewModel,
                    communityViewModel = communityViewModel,
                )
            }

            composable(
                route = Routes.ROOM,
                arguments = listOf(navArgument(Routes.Arg.ROOM) { type = NavType.StringType }),
            ) { entry ->
                RoomDetailScreen(
                    room = entry.room(),
                    onBack = navController::popBackStack,
                    communityViewModel = communityViewModel,
                )
            }
        }
        }
        // Overlays the nav graph when a rollover is required. Rendered after the Scaffold so it
        // sits on top, and only composes content while the state is not Idle.
        RolloverGate(viewModel = rolloverViewModel)
        // And the same way, for the one sentence that is about the install rather than about a
        // screen: the account was removed by somebody else, so the student was signed out and
        // told nothing. Composes nothing unless that is true and unacknowledged.
        RemovedAccountNotice(viewModel = removedAccountNoticeViewModel)
    }
}

private enum class Tab(
    val route: String,
    val label: String,
) {
    ATTENDANCE(Routes.DASHBOARD, "Attendance"),
    ROOMS(Routes.ROOMS, "Rooms"),
}

/** Read where it is drawn: one of these icons is a resource, and resources need a composition. */
@Composable
private fun Tab.icon(): ImageVector = when (this) {
    Tab.ATTENDANCE -> Icons.Filled.CheckCircle
    Tab.ROOMS -> AttendoIcons.MeetingRoom
}

/** The course id on a route that carries one. Falls back to "new", which is id 0. */
private fun NavBackStackEntry.courseId(): Long =
    arguments?.getLong(Routes.Arg.COURSE_ID) ?: Routes.NEW

/**
 * The room name on a route that carries one.
 *
 * [Routes.room] percent-encodes it because "Basement Lab" has a space in it. Navigation
 * decodes path arguments on the way in, so the extra [Uri.decode] here is belt and braces
 * — idempotent on every room name the timetable contains.
 */
private fun NavBackStackEntry.room(): String =
    arguments?.getString(Routes.Arg.ROOM)?.let(Uri::decode).orEmpty()

@Composable
private fun AttendoNavBar(
    current: String?,
    onSelect: (String) -> Unit,
) {
    NavigationBar {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = current == tab.route,
                onClick = { onSelect(tab.route) },
                icon = { Icon(tab.icon(), contentDescription = null) },
                label = { Text(tab.label) },
            )
        }
    }
}

/**
 * Tab switching that keeps each tab's scroll position and does not stack copies of a tab
 * on the back stack — the standard bottom-bar behaviour, which needs saying explicitly
 * because the default is neither.
 */
private fun NavHostController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

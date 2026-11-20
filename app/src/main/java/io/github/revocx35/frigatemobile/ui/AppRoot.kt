package io.github.revocx35.frigatemobile.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.revocx35.frigatemobile.data.AppGraph
import io.github.revocx35.frigatemobile.data.AppSettings
import io.github.revocx35.frigatemobile.data.Connection
import io.github.revocx35.frigatemobile.ui.cameras.CameraScreen
import io.github.revocx35.frigatemobile.ui.cameras.CamerasScreen
import io.github.revocx35.frigatemobile.ui.components.LocalConnection
import io.github.revocx35.frigatemobile.ui.login.LoginScreen
import io.github.revocx35.frigatemobile.ui.player.PlayerScreen
import io.github.revocx35.frigatemobile.ui.recordings.RecordingsScreen
import io.github.revocx35.frigatemobile.ui.review.ReviewDetailScreen
import io.github.revocx35.frigatemobile.ui.review.ReviewScreen
import io.github.revocx35.frigatemobile.ui.system.SystemScreen
import io.github.revocx35.frigatemobile.ui.theme.AppIcons
import io.github.revocx35.frigatemobile.ui.theme.FrigateTheme
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun AppRoot(graph: AppGraph, openReview: MutableStateFlow<String?>) {
    val settings by graph.settings.settings.collectAsState()
    val connection by graph.connection.collectAsState()
    val reason by graph.signedOutReason.collectAsState()
    FrigateTheme(settings.theme) {
      // Test tags double as resource ids, so UI Automator (scripts/e2e-release.py) can find elements.
      Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        val conn = connection
        if (conn == null) {
            LoginScreen(graph, reason)
        } else {
            CompositionLocalProvider(LocalConnection provides conn) {
                // Re-check the role (an admin may have changed it) and keep it current.
                LaunchedEffect(conn.session) { runCatching { graph.updateRole(conn.client.profile().role) } }
                MainNav(graph, conn, settings, openReview)
            }
        }
      }
    }
}

private fun enc(s: String) = Uri.encode(s)

@Composable
private fun MainNav(graph: AppGraph, conn: Connection, settings: AppSettings, openReview: MutableStateFlow<String?>) {
    val nav = rememberNavController()
    val pending by openReview.collectAsState()
    LaunchedEffect(pending) {
        pending?.let {
            nav.navigate("review/${enc(it)}") { launchSingleTop = true }
            openReview.value = null
        }
    }
    NavHost(nav, startDestination = "main", modifier = Modifier.fillMaxSize()) {
        composable("main") {
            MainTabs(
                graph = graph,
                onOpenCamera = { nav.navigate("camera/${enc(it)}") },
                onOpenReview = { nav.navigate("review/${enc(it)}") },
            )
        }
        composable("camera/{name}", arguments = listOf(navArgument("name") { type = NavType.StringType })) { e ->
            val name = e.arguments?.getString("name").orEmpty()
            CameraScreen(
                camera = name,
                settings = settings,
                store = graph.settings,
                onBack = { nav.popBackStack() },
                onOpenReview = { nav.navigate("review/${enc(it)}") },
                onOpenRecordings = { nav.navigate("recordings/${enc(it)}") },
            )
        }
        composable("review/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { e ->
            ReviewDetailScreen(e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable("recordings/{camera}", arguments = listOf(navArgument("camera") { type = NavType.StringType })) { e ->
            val camera = e.arguments?.getString("camera").orEmpty()
            RecordingsScreen(camera, onBack = { nav.popBackStack() }) { title, start, end ->
                nav.navigate("play/${enc(camera)}/${start.toLong()}/${end.toLong()}?title=${enc(title)}")
            }
        }
        composable(
            "play/{camera}/{start}/{end}?title={title}",
            arguments = listOf(
                navArgument("camera") { type = NavType.StringType },
                navArgument("start") { type = NavType.LongType },
                navArgument("end") { type = NavType.LongType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { e ->
            val a = e.arguments!!
            val url = conn.client.urls.vod(a.getString("camera").orEmpty(), a.getLong("start").toDouble(), a.getLong("end").toDouble()).toString()
            PlayerScreen(a.getString("title").orEmpty(), url, onBack = { nav.popBackStack() })
        }
    }
}

@Composable
private fun MainTabs(graph: AppGraph, onOpenCamera: (String) -> Unit, onOpenReview: (String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, icon = { Icon(AppIcons.Videocam, null) }, label = { Text("Cameras") }, modifier = Modifier.testTag("tab_cameras"))
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, icon = { Icon(AppIcons.Review, null) }, label = { Text("Review") }, modifier = Modifier.testTag("tab_review"))
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, icon = { Icon(AppIcons.Settings, null) }, label = { Text("System") }, modifier = Modifier.testTag("tab_system"))
            }
        },
    ) { padding ->
        Box(Modifier.padding(bottom = padding.calculateBottomPadding()).consumeWindowInsets(padding)) {
            when (tab) {
                0 -> CamerasScreen(onOpen = onOpenCamera)
                1 -> ReviewScreen(onOpen = onOpenReview)
                else -> SystemScreen(graph)
            }
        }
    }
}

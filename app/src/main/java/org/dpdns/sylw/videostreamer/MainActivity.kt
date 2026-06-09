package org.dpdns.sylw.videostreamer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.launch
import org.dpdns.sylw.videostreamer.ui.theme.VideoStreamerTheme


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            var appLanguage by remember { mutableStateOf(resolveDefaultAppLanguage()) }

            LaunchedEffect(Unit) {
                appLanguage = loadAppLanguage(context)
                StreamConfig.setAppLanguage(appLanguage)
            }

            AppLocaleProvider(appLanguage) {
                VideoStreamerTheme {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        WindowForSelecting(
                            appLanguage = appLanguage,
                            onLanguageChange = { language ->
                                appLanguage = normalizeAppLanguage(language)
                                StreamConfig.setAppLanguage(appLanguage)
                            },
                            modifier = Modifier.padding(innerPadding)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WindowForSelecting(
    modifier: Modifier = Modifier,
    appLanguage: String = resolveDefaultAppLanguage(),
    onLanguageChange: (String) -> Unit = {}
) {

    val tabs = listOf(
        stringResource(R.string.tab_settings),
        stringResource(R.string.tab_video),
        stringResource(R.string.tab_camera)
    )
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize()) {

        TabRow(selectedTabIndex = pagerState.currentPage) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    text = { Text(title) },
                    selected = pagerState.currentPage == index,
                    onClick = {
                        scope.launch {
                            pagerState.animateScrollToPage(index)
                        }
                    }
                )
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->

            val focusManager = LocalFocusManager.current
            LaunchedEffect(pagerState.currentPage) {
                focusManager.clearFocus()
            }

            when (page) {
                0 -> SettingWindow(
                    selectedLanguage = appLanguage,
                    onLanguageChange = onLanguageChange
                )
                1 -> VideoWindow()
                2 -> CameraWindow()
            }

        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    VideoStreamerTheme {
        WindowForSelecting()
    }
}

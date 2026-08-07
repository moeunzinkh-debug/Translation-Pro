package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.repository.TranslationRepository
import com.example.data.security.SecureSettingsRepository
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SubtitleScreen
import com.example.ui.screens.TranslationScreen
import com.example.ui.theme.TranslateProTheme
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.SubtitleViewModel
import com.example.ui.viewmodel.TranslationViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settingsRepo = SecureSettingsRepository(applicationContext)
        val translationRepo = TranslationRepository(settingsRepo)

        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return when {
                    modelClass.isAssignableFrom(TranslationViewModel::class.java) ->
                        TranslationViewModel(translationRepo, settingsRepo) as T
                    modelClass.isAssignableFrom(SubtitleViewModel::class.java) ->
                        SubtitleViewModel(translationRepo, settingsRepo) as T
                    modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
                        SettingsViewModel(settingsRepo, translationRepo) as T
                    else -> throw IllegalArgumentException("Unknown ViewModel class ${modelClass.name}")
                }
            }
        }

        setContent {
            TranslateProTheme {
                val translationViewModel: TranslationViewModel = viewModel(factory = factory)
                val subtitleViewModel: SubtitleViewModel = viewModel(factory = factory)
                val settingsViewModel: SettingsViewModel = viewModel(factory = factory)

                TranslateProApp(
                    translationViewModel = translationViewModel,
                    subtitleViewModel = subtitleViewModel,
                    settingsViewModel = settingsViewModel
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateProApp(
    translationViewModel: TranslationViewModel,
    subtitleViewModel: SubtitleViewModel,
    settingsViewModel: SettingsViewModel
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    val titles = listOf("Translate Pro", "Subtitle Translator", "API Key Settings")

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = titles[selectedTab],
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.testTag("bottom_navigation_bar")
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Translate, contentDescription = "Text Translation") },
                    label = { Text("Text") },
                    modifier = Modifier.testTag("nav_item_text")
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Subtitles, contentDescription = "Subtitle Translator") },
                    label = { Text("Subtitles") },
                    modifier = Modifier.testTag("nav_item_subtitles")
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                    label = { Text("Settings") },
                    modifier = Modifier.testTag("nav_item_settings")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> TranslationScreen(
                    viewModel = translationViewModel,
                    onNavigateToSettings = { selectedTab = 2 }
                )
                1 -> SubtitleScreen(viewModel = subtitleViewModel)
                2 -> SettingsScreen(viewModel = settingsViewModel)
            }
        }
    }
}

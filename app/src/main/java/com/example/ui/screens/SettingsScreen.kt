package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AiProvider
import com.example.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadSettings() }
    val scrollState = rememberScrollState()

    var providerDropdownExpanded by remember { mutableStateOf(false) }
    var geminiModelDropdownExpanded by remember { mutableStateOf(false) }
    var keyVisible by remember { mutableStateOf(false) }
    var newGeminiKey by remember { mutableStateOf("") }
    var newGeminiLabel by remember { mutableStateOf("") }
    var newGeminiLimit by remember { mutableStateOf("20") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Security Banner
        ElevatedCard(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Encrypted Local Key Storage",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Your API keys are stored securely on this device using EncryptedSharedPreferences (AES-256).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // Active Provider Selector
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Active AI Provider",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Choose which model translates your text and subtitles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Box(modifier = Modifier.fillMaxWidth()) {
                    Surface(
                        onClick = { providerDropdownExpanded = true },
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("provider_dropdown")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = state.selectedProvider.displayName,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = state.selectedProvider.description,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                            Text("Change ▾", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        }
                    }

                    DropdownMenu(
                        expanded = providerDropdownExpanded,
                        onDismissRequest = { providerDropdownExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        AiProvider.entries.forEach { provider ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(provider.displayName, fontWeight = FontWeight.Bold)
                                        Text(provider.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = {
                                    viewModel.onSelectProvider(provider)
                                    providerDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        // Active Provider Key & Configuration Form
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "${state.selectedProvider.displayName} Settings",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(16.dp))

                when (state.selectedProvider) {
                    AiProvider.SEA_LION -> {
                        // Sea-Lion Key
                        OutlinedTextField(
                            value = state.seaLionApiKey,
                            onValueChange = { viewModel.onSeaLionApiKeyChanged(it) },
                            label = { Text("Sea-Lion API Key / Token") },
                            placeholder = { Text("e.g. sl-key-12345...") },
                            singleLine = true,
                            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { keyVisible = !keyVisible }) {
                                    Icon(
                                        imageVector = if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle Visibility"
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("sealion_api_key_field")
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Sea-Lion Base URL
                        OutlinedTextField(
                            value = state.seaLionBaseUrl,
                            onValueChange = { viewModel.onSeaLionBaseUrlChanged(it) },
                            label = { Text("Sea-Lion Base URL") },
                            placeholder = { Text("https://api.sea-lion.ai/v1/") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Sea-Lion Model Name
                        OutlinedTextField(
                            value = state.seaLionModel,
                            onValueChange = { viewModel.onSeaLionModelChanged(it) },
                            label = { Text("Model Name") },
                            placeholder = { Text("aisingapore/sea-lion-7b-instruct") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    AiProvider.GEMINI -> {
                        Text(
                            "Gemini key pool",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Add as many keys as needed. Remaining requests are based on your app-managed daily limit; Google does not expose live project quota through an API key.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "When Gemini returns a quota or rate-limit response, the app marks that key unavailable for today and automatically switches to the next available key.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Spacer(Modifier.height(10.dp))

                        state.geminiKeys.forEach { key ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (key.value == state.geminiApiKey) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.selectGeminiKey(key.id) }
                            ) {
                                Row(
                                    Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(key.label, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "${key.maskedValue} · ${key.remainingToday} / ${key.dailyLimit} requests left today",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    TextButton(onClick = { viewModel.removeGeminiKey(key.id) }) {
                                        Text("Remove")
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }

                        OutlinedTextField(
                            value = newGeminiLabel,
                            onValueChange = { newGeminiLabel = it },
                            label = { Text("Key label") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = newGeminiKey,
                            onValueChange = { newGeminiKey = it },
                            label = { Text("New Gemini API key") },
                            placeholder = { Text("AIza...") },
                            singleLine = true,
                            visualTransformation = if (keyVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { keyVisible = !keyVisible }) {
                                    Icon(
                                        if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "Toggle API key visibility"
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("gemini_api_key_field")
                        )
                        OutlinedTextField(
                            value = newGeminiLimit,
                            onValueChange = { newGeminiLimit = it.filter(Char::isDigit) },
                            label = { Text("Daily request budget") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            onClick = {
                                viewModel.addGeminiKey(
                                    newGeminiLabel,
                                    newGeminiKey,
                                    newGeminiLimit.toIntOrNull() ?: 20
                                )
                                newGeminiKey = ""
                                newGeminiLabel = ""
                            },
                            enabled = newGeminiKey.isNotBlank(),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Add Gemini key")
                        }

                        Spacer(Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Gemini model",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    if (state.availableGeminiModels.isEmpty()) {
                                        "Load all compatible models from Google"
                                    } else {
                                        "${state.availableGeminiModels.size} compatible models loaded"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(
                                onClick = { viewModel.refreshGeminiModels() },
                                enabled = !state.isLoadingGeminiModels && state.geminiApiKey.isNotBlank()
                            ) {
                                if (state.isLoadingGeminiModels) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = null)
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(if (state.availableGeminiModels.isEmpty()) "Load all" else "Refresh")
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Box(modifier = Modifier.fillMaxWidth()) {
                            val selectedModel = state.availableGeminiModels
                                .firstOrNull { it.id == state.geminiModel }
                            Surface(
                                onClick = {
                                    if (state.availableGeminiModels.isEmpty()) {
                                        viewModel.refreshGeminiModels()
                                    } else {
                                        geminiModelDropdownExpanded = true
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("gemini_model_dropdown")
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            selectedModel?.displayName ?: state.geminiModel,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        if (selectedModel != null && selectedModel.displayName != selectedModel.id) {
                                            Text(
                                                selectedModel.id,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        contentDescription = "Choose Gemini model"
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = geminiModelDropdownExpanded,
                                onDismissRequest = { geminiModelDropdownExpanded = false },
                                modifier = Modifier
                                    .fillMaxWidth(0.95f)
                                    .heightIn(max = 480.dp)
                            ) {
                                state.availableGeminiModels.forEach { model ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(verticalAlignment = Alignment.Top) {
                                                if (model.id == state.geminiModel) {
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = "Selected",
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    Spacer(Modifier.width(8.dp))
                                                }
                                                Column {
                                                    Text(
                                                        model.displayName,
                                                        fontWeight = if (model.id == state.geminiModel) {
                                                            FontWeight.Bold
                                                        } else {
                                                            FontWeight.SemiBold
                                                        }
                                                    )
                                                    Text(
                                                        model.id,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    if (model.isLegacyForNewUsers) {
                                                        Text(
                                                            "Legacy — may be unavailable to new API keys",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.error
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                        onClick = {
                                            viewModel.onGeminiModelChanged(model.id)
                                            geminiModelDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        if (state.geminiModel.startsWith("gemini-2.")) {
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.errorContainer,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    "Gemini 2.x can be unavailable to new API keys. Choose a current Gemini 3 model from the full list above.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        state.geminiModelsError?.let { error ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    AiProvider.CHATGPT -> {
                        OutlinedTextField(
                            value = state.chatGptApiKey,
                            onValueChange = { viewModel.onChatGptApiKeyChanged(it) },
                            label = { Text("OpenAI API Key") },
                            placeholder = { Text("sk-proj-...") },
                            singleLine = true,
                            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                IconButton(onClick = { keyVisible = !keyVisible }) {
                                    Icon(
                                        imageVector = if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = null
                                    )
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("chatgpt_api_key_field")
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = state.chatGptModel,
                            onValueChange = { viewModel.onChatGptModelChanged(it) },
                            label = { Text("OpenAI Model Name") },
                            placeholder = { Text("gpt-4o-mini") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    AiProvider.CUSTOM -> {
                        OutlinedTextField(
                            value = state.customApiKey,
                            onValueChange = { viewModel.onCustomApiKeyChanged(it) },
                            label = { Text("Custom API Key / Token") },
                            singleLine = true,
                            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = state.customBaseUrl,
                            onValueChange = { viewModel.onCustomBaseUrlChanged(it) },
                            label = { Text("Custom Endpoint Base URL") },
                            placeholder = { Text("https://my-custom-endpoint/v1/") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = state.customModel,
                            onValueChange = { viewModel.onCustomModelChanged(it) },
                            label = { Text("Custom Model Name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Connection Tester Button
                Button(
                    onClick = { viewModel.testConnection() },
                    enabled = !state.isTestingConnection,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("test_connection_button")
                ) {
                    if (state.isTestingConnection) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Testing API Connection...")
                    } else {
                        Icon(imageVector = Icons.Default.NetworkCheck, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test API Connection")
                    }
                }

                // Connection Test Feedback Banner
                AnimatedVisibility(
                    visible = state.testConnectionResult != null || state.testConnectionError != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    if (state.testConnectionResult != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = state.testConnectionResult ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    } else if (state.testConnectionError != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = state.testConnectionError ?: "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

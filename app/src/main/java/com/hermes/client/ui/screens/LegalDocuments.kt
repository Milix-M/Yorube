package com.hermes.client.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hermes.client.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Documents ship in the APK: no network, external viewer, or JavaScript. */
@Composable
internal fun LegalDocumentLinks() {
    val documents = listOf(
        "PRIVACY.txt" to R.string.privacy_policy,
        "LICENSES.txt" to R.string.open_source_licenses,
    )
    var selected by remember { mutableStateOf<Pair<String, Int>?>(null) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        documents.forEach { document ->
            TextButton(onClick = { selected = document }, modifier = Modifier.testTag(document.first)) {
                Text(stringResource(document.second))
            }
        }
    }
    selected?.let { document ->
        LegalDocumentDialog(document.first, stringResource(document.second), onDismiss = { selected = null })
    }
}

@Composable
internal fun LegalDocumentDialog(asset: String, title: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val paragraphs by produceState(initialValue = emptyList<String>(), asset) {
        value = withContext(Dispatchers.IO) {
            context.assets.open("legal/$asset").bufferedReader().use { it.readText() }.split("\n\n")
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
        text = {
            // Virtualize the long notices instead of measuring all text at once.
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).testTag("legalDocument"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(paragraphs) { paragraph ->
                    SelectionContainer { Text(paragraph, style = MaterialTheme.typography.bodySmall) }
                }
            }
        },
    )
}

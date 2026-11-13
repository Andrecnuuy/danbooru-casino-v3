@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.andrecnuuy.assetcasino

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.andrecnuuy.assetcasino.core.MediaKind
import com.andrecnuuy.assetcasino.core.PreviewSpec
import com.andrecnuuy.assetcasino.core.StatRow
import com.andrecnuuy.assetcasino.core.StatsBuilder
import com.andrecnuuy.assetcasino.core.TagType
import com.andrecnuuy.assetcasino.core.VideoRef
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ================================================================================================
// App shell
// ================================================================================================

@Composable
fun CasinoApp(vm: CasinoViewModel) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        vm.toasts.collect { msg -> Toast.makeText(context, msg, Toast.LENGTH_LONG).show() }
    }
    val view = LocalView.current
    LaunchedEffect(vm.busy) { view.keepScreenOn = vm.busy }
    BackHandler(enabled = vm.screen == Screen.STATS) { vm.closeStats() }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        when (vm.screen) {
            Screen.HOME -> HomeScreen(vm)
            Screen.STATS -> StatsScreen(vm)
        }
    }

    val pending = vm.pendingImport
    if (pending != null) {
        AlertDialog(
            onDismissRequest = { vm.cancelImport() },
            title = { Text("Replace the database?") },
            text = {
                Text(
                    "The file contains ${pending.db.items.size} uploads (scan " +
                        (if (pending.db.state.complete) "complete" else "incomplete") +
                        "). Your current database has ${vm.dbCount} uploads and will be replaced."
                )
            },
            confirmButton = { TextButton(onClick = { vm.confirmImport() }) { Text("Replace") } },
            dismissButton = { TextButton(onClick = { vm.cancelImport() }) { Text("Cancel") } }
        )
    }
    if (vm.confirmClear) {
        AlertDialog(
            onDismissRequest = { vm.cancelClear() },
            title = { Text("Clear the database?") },
            text = { Text("All ${vm.dbCount} scanned uploads and the scan history are deleted from this phone. Export first if you may want them back.") },
            confirmButton = { TextButton(onClick = { vm.clearDb() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { vm.cancelClear() }) { Text("Cancel") } }
        )
    }
    val spec = vm.preview
    if (spec != null) PreviewDialog(vm, spec)
}

@Composable
fun Section(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Gold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
fun HomeScreen(vm: CasinoViewModel) {
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Header() }
            item { CredentialsCard(vm) }
            item { DatabaseCard(vm) }
            item { CasinoCard(vm) }
            if (vm.rolling) {
                item { RollingCard(vm) }
            }
            val w = vm.winner
            if (w != null) {
                item { ResultCard(vm, w) }
                if (vm.mode == Mode.SHARING) {
                    val ref = videoRefOf(w)
                    if (ref != null) {
                        item { VideoInfoCard(vm, ref) }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("🎰 Danbooru Asset Casino", style = MaterialTheme.typography.headlineSmall, color = Gold)
        Text(
            "Pull a random asset from your media assets to upload or share.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ================================================================================================
// Credentials
// ================================================================================================

@Composable
fun CredentialsCard(vm: CasinoViewModel) {
    var showKey by remember { mutableStateOf(false) }
    Section("Danbooru account", "Used to scan your media assets and to look up artists. Stored only on this phone.") {
        OutlinedTextField(
            value = vm.login,
            onValueChange = { vm.updateLogin(it) },
            label = { Text("Login (username)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = vm.apiKey,
            onValueChange = { vm.updateApiKey(it) },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.saveCredentials() }, enabled = !vm.verifying) { Text("Save & verify") }
            if (vm.verifying) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        if (vm.accountText.isNotBlank()) {
            Text(vm.accountText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ================================================================================================
// Media asset database (scan / export / import / DAO)
// ================================================================================================

private fun fmtTime(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))

@Composable
fun DatabaseCard(vm: CasinoViewModel) {
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) vm.exportDb(uri) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importDb(uri) }
    val daoLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.importDao(uri) }
    var showHistory by remember { mutableStateOf(false) }

    Section("Media asset scan", "Finds your unposted uploads with their upload id, source and category. Later scans only fetch new ones and stop at the checkpoint.") {
        val s = vm.scanState
        val status = when {
            !vm.loaded -> "Loading database..."
            vm.dbCount == 0 -> "Database is empty - run a scan."
            s.complete -> "${vm.dbCount} uploads in the database - scan complete."
            else -> "${vm.dbCount} uploads in the database - scan incomplete, scan again to finish."
        }
        Text(status, style = MaterialTheme.typography.bodyMedium)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.busy) {
                Button(
                    onClick = { vm.requestStop() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) { Text("Stop") }
            } else {
                Button(onClick = { vm.startScan() }, enabled = vm.loaded) {
                    Text(if (vm.dbCount == 0) "Scan" else "Scan new")
                }
            }
            OutlinedButton(onClick = { vm.openStats() }, enabled = !vm.busy && vm.dbCount > 0) { Text("Statistics") }
            OutlinedButton(onClick = { vm.purgePosted() }, enabled = !vm.busy && vm.dbCount > 0) { Text("Purge posted") }
        }

        if (vm.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(vm.busyLabel, style = MaterialTheme.typography.labelLarge)
            if (vm.progressText.isNotBlank()) {
                Text(vm.progressText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                    exportLauncher.launch("asset-casino-db-$stamp.json")
                },
                enabled = !vm.busy && vm.dbCount > 0
            ) { Text("Export") }
            OutlinedButton(
                onClick = { importLauncher.launch(arrayOf("*/*")) },
                enabled = !vm.busy
            ) { Text("Import") }
            OutlinedButton(
                onClick = { daoLauncher.launch(arrayOf("*/*")) },
                enabled = !vm.busy
            ) { Text("Import DAO database") }
            TextButton(onClick = { vm.askClear() }, enabled = !vm.busy && vm.dbCount > 0) { Text("Clear") }
        }

        val dao = vm.dao
        Text(
            if (dao == null) {
                "DAO database: not imported (needed for artist / copyright / character filters)."
            } else {
                "DAO database: tags for ${dao.entries.size} uploads, imported ${fmtTime(dao.importedAt)}" +
                    (if (dao.sourceVersion.isNotBlank()) " (DAO ${dao.sourceVersion})" else "") + "."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (vm.history.isNotEmpty()) {
            TextButton(onClick = { showHistory = !showHistory }) {
                Text(if (showHistory) "Hide scan history" else "Scan history (${vm.history.size})")
            }
            if (showHistory) {
                for (h in vm.history) {
                    Column {
                        Text(
                            "${fmtTime(h.at)} - ${h.mode}: ${h.result}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            "+${h.added} new, ${h.total} total, ${h.pages} pages" + (if (h.note.isNotBlank()) " - ${h.note}" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

// ================================================================================================
// The casino
// ================================================================================================

@Composable
fun CasinoCard(vm: CasinoViewModel) {
    val n = vm.pool.size
    Section("The casino") {
        TabRow(selectedTabIndex = vm.mode.ordinal) {
            Tab(selected = vm.mode == Mode.UPLOAD, onClick = { vm.changeMode(Mode.UPLOAD) }, text = { Text("Upload mode") })
            Tab(selected = vm.mode == Mode.SHARING, onClick = { vm.changeMode(Mode.SHARING) }, text = { Text("Sharing mode") })
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Possible assets", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (vm.dbCount > 0 && n != vm.dbCount) {
                    Text("of ${vm.dbCount} in the database (filters applied)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(n.toString(), style = MaterialTheme.typography.headlineMedium, color = Gold)
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = vm.numberText,
                onValueChange = { vm.updateNumber(it.filter { c -> c.isDigit() }.take(9)) },
                label = { Text("Pick a number (1 to $n)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = { vm.randomizeNumber() }, enabled = n > 0) { Text("🎲 Random") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = vm.shuffle, onCheckedChange = { vm.changeShuffle(it) })
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Shuffle numbering", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (vm.shuffle) "Numbers map to assets in a random order." else "Numbers run from the oldest (1) to the newest.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (vm.shuffle) TextButton(onClick = { vm.reshuffle() }) { Text("Reshuffle") }
        }

        Button(
            onClick = { vm.gamba() },
            enabled = n > 0 && !vm.busy && !vm.rolling,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF1B1426))
        ) { Text("🎰  GAMBA", style = MaterialTheme.typography.titleMedium) }

        CustomizeSection(vm)

        if (vm.mode == Mode.SHARING) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
            Text("Share message (optional)", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = vm.prefix,
                onValueChange = { vm.updatePrefix(it) },
                label = { Text("Prefix - text above the link") },
                minLines = 1,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = vm.affix,
                onValueChange = { vm.updateAffix(it) },
                label = { Text("Affix - text below the link") },
                minLines = 1,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun CustomizeSection(vm: CasinoViewModel) {
    var open by remember { mutableStateOf(false) }
    val f = vm.filters
    val activeCount = f.categories.size + f.kinds.size + f.artists.size + f.copyrights.size + f.characters.size

    OutlinedButton(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
        Text(
            (if (open) "Hide customization" else "Customize pool") +
                (if (activeCount > 0) "  ($activeCount filter" + (if (activeCount == 1) "" else "s") + " on)" else "")
        )
    }
    if (!open) return

    Text("Source", style = MaterialTheme.typography.labelLarge)
    if (vm.categoryOptions.isEmpty()) {
        Text("Scan your media assets first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (c in vm.categoryOptions) {
            FilterChip(
                selected = c.name in vm.selCategories,
                onClick = { vm.toggleCategory(c.name) },
                label = { Text("${c.name} (${c.count})") }
            )
        }
    }

    Text("Media type", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (k in MediaKind.values()) {
            FilterChip(
                selected = k in vm.selKinds,
                onClick = { vm.toggleKind(k) },
                label = { Text(k.label) }
            )
        }
    }

    Text("Tags (artist / copyright / character)", style = MaterialTheme.typography.labelLarge)
    if (vm.dao == null) {
        Text(
            "Import your DAO database above to filter by tag.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else {
        TagPicker(vm)
    }
    Text(
        "Several choices in one group match any of them; different groups must all match.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    TextButton(onClick = { vm.clearFilters() }, enabled = activeCount > 0) { Text("Clear all filters") }
}

private fun tagColor(type: TagType): Color = when (type) {
    TagType.ARTIST -> TagArtist
    TagType.COPYRIGHT -> TagCopyright
    TagType.CHARACTER -> TagCharacter
}

@Composable
fun TagPicker(vm: CasinoViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val types = TagType.values()
    val type = types[tab]
    val options = when (type) {
        TagType.ARTIST -> vm.artistOptions
        TagType.COPYRIGHT -> vm.copyrightOptions
        TagType.CHARACTER -> vm.characterOptions
    }
    val selected = vm.selectedTags(type)
    val shown = remember(options, query) {
        val q = query.trim()
        if (q.isEmpty()) options else options.filter { it.name.contains(q, ignoreCase = true) }
    }

    // Everything already chosen, across the three groups.
    val chosen = ArrayList<Pair<TagType, String>>()
    for (t in types) for (name in vm.selectedTags(t)) chosen.add(t to name)
    if (chosen.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((t, name) in chosen) {
                FilterChip(
                    selected = true,
                    onClick = { vm.toggleTag(t, name) },
                    label = { Text("$name  ✕", color = tagColor(t)) }
                )
            }
        }
    }

    val tabCounts = listOf(vm.artistOptions.size, vm.copyrightOptions.size, vm.characterOptions.size)
    TabRow(selectedTabIndex = tab) {
        types.forEachIndexed { i, t ->
            Tab(
                selected = tab == i,
                onClick = { tab = i },
                text = { Text(t.label + " (" + tabCounts[i] + ")") }
            )
        }
    }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Search ${type.label.lowercase()} tags") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        "${shown.size} tags, most used first",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(shown, key = { it.name }) { tc ->
                val isOn = tc.name in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.toggleTag(type, tc.name) }
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = isOn, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        tc.name,
                        color = tagColor(type),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(tc.count.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ================================================================================================
// Result
// ================================================================================================

@Composable
fun RollingCard(vm: CasinoViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(modifier = Modifier
            .fillMaxWidth()
            .padding(28.dp), contentAlignment = Alignment.Center) {
            Text("🎰 ${vm.rollingText}", style = MaterialTheme.typography.displaySmall, color = Gold)
        }
    }
}

@Composable
fun LinkBlock(label: String, url: String) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { copyToClipboard(ctx, url, "Link copied") }) { Text("Copy") }
            OutlinedButton(onClick = { openInBrowser(ctx, url) }) { Text("Open in browser") }
        }
    }
}

@Composable
fun ResultCard(vm: CasinoViewModel, w: Winner) {
    val ctx = LocalContext.current
    val e = w.row.e
    val asset = w.asset
    val detail = vm.detail

    Section("🎉 Winner #${w.number}") {
        Text(
            "Upload #${e.id}  ·  Asset #${asset.id}\n" +
                "${asset.ext.uppercase()}  ·  ${asset.w}×${asset.h}  ·  ${StatsBuilder.humanSize(asset.size)}" +
                (if (asset.dur > 0) "  ·  ${StatsBuilder.humanDuration(asset.dur)}" else "") + "\n" +
                w.row.category + (if (e.createdAt > 0) "  ·  uploaded ${StatsBuilder.date(e.createdAt)}" else ""),
            style = MaterialTheme.typography.bodyMedium
        )
        if (w.row.artists.isNotEmpty()) {
            Text("Artist: " + w.row.artists.take(4).joinToString(", "), color = TagArtist, style = MaterialTheme.typography.bodySmall)
        }
        if (w.row.copyrights.isNotEmpty()) {
            Text("Copyright: " + w.row.copyrights.take(4).joinToString(", "), color = TagCopyright, style = MaterialTheme.typography.bodySmall)
        }
        if (w.row.characters.isNotEmpty()) {
            Text("Character: " + w.row.characters.take(6).joinToString(", "), color = TagCharacter, style = MaterialTheme.typography.bodySmall)
        }

        if (e.media.size > 1) {
            val postedId = (detail as? DetailState.Ready)?.detail?.assets?.firstOrNull { it.mediaId == asset.id }?.postId
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.selectAsset(w.assetIndex - 1) }, enabled = w.assetIndex > 0) { Text("◀") }
                Text(
                    "Asset ${w.assetIndex + 1} of ${e.media.size}" + (if (postedId != null) " (already posted)" else ""),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(onClick = { vm.selectAsset(w.assetIndex + 1) }, enabled = w.assetIndex < e.media.size - 1) { Text("▶") }
            }
        }

        when (detail) {
            is DetailState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Checking the asset on Danbooru...", style = MaterialTheme.typography.bodySmall)
            }
            is DetailState.Failed -> Text(detail.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            is DetailState.Posted -> Text(
                "Heads up: this upload has already been posted (post #${detail.postId}). It was removed from your database - gamba again.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
            else -> {}
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

        if (vm.mode == Mode.UPLOAD) {
            LinkBlock("Upload page", vm.uploadLink(w))
        } else {
            LinkBlock("Media asset link", vm.mediaLink(w))
            val source = shareSourceOf(w)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = vm.includeSource && source != null, onCheckedChange = { vm.changeIncludeSource(it) }, enabled = source != null)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Include the source link", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (source == null) "This asset has no web source." else source,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val message = vm.shareMessage(w)
            Text("Message", style = MaterialTheme.typography.labelLarge)
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                Text(
                    message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { copyToClipboard(ctx, message, "Message copied") }) { Text("Copy message") }
                OutlinedButton(onClick = { copyToClipboard(ctx, vm.mediaLink(w), "Link copied") }) { Text("Copy link only") }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.openPreview() }, enabled = detail is DetailState.Ready) { Text("Preview") }
            OutlinedButton(onClick = { vm.download() }, enabled = detail is DetailState.Ready && !vm.downloading) { Text("Download") }
        }
        if (vm.downloadStatus.isNotBlank()) {
            Text(vm.downloadStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (e.media.size > 1) {
            Text(
                "Multi-asset upload: preview and download use the selected asset (the first one by default).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun VideoInfoCard(vm: CasinoViewModel, ref: VideoRef) {
    val ctx = LocalContext.current
    Section(
        "${ref.platform.label} video info",
        "This direct upload's file name carries a ${ref.platform.label} id, so its page can be read to collect everything needed to upload it."
    ) {
        Text(ref.url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        val state = vm.videoState
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.gatherVideoInfo() }, enabled = state !is VideoState.Loading) {
                Text(if (state is VideoState.Done) "Gather again" else "Gather info")
            }
            if (state is VideoState.Loading) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        when (state) {
            is VideoState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            is VideoState.Done -> {
                OutlinedTextField(
                    value = vm.videoText,
                    onValueChange = { vm.updateVideoText(it) },
                    label = { Text("Title / description / source / artist (editable)") },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                )
                Button(onClick = { copyToClipboard(ctx, vm.videoText, "Info copied") }) { Text("Copy info") }
                for (m in state.info.missing) {
                    OutlinedButton(
                        onClick = { openInBrowser(ctx, m.registrationUrl()) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Open new-artist page: ${m.name.ifBlank { m.original }}") }
                }
                for (note in state.info.notes) {
                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {}
        }
    }
}

// ================================================================================================
// Preview dialog
// ================================================================================================

@Composable
fun PreviewDialog(vm: CasinoViewModel, spec: PreviewSpec) {
    Dialog(
        onDismissRequest = { vm.closePreview() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                if (spec.isVideo) {
                    VideoPlayer(client = vm.client, url = spec.url, modifier = Modifier.fillMaxSize())
                } else {
                    AsyncImage(
                        model = spec.url,
                        imageLoader = vm.imageLoader,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                        onError = { vm.toast("Preview failed to load: ${it.result.throwable.message ?: "unknown error"}") }
                    )
                }
                TextButton(
                    onClick = { vm.closePreview() },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                ) { Text("Close", color = Color.White) }
            }
        }
    }
}

// ================================================================================================
// Statistics
// ================================================================================================

@Composable
fun BarRow(row: StatRow, max: Double) {
    val frac = if (max > 0) (row.value / max).toFloat().coerceIn(0f, 1f) else 0f
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(row.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(row.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(frac)
                    .fillMaxHeight()
                    .background(Gold, RoundedCornerShape(3.dp))
            )
        }
    }
}

@Composable
fun StatsScreen(vm: CasinoViewModel) {
    val report = vm.statsReport
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { vm.closeStats() }) { Text("← Back") }
                    Spacer(Modifier.width(8.dp))
                    Text("Statistics", style = MaterialTheme.typography.headlineSmall, color = Gold)
                }
            }
            if (report == null) {
                item { Text("No data yet.") }
            } else {
                item {
                    Section("Overview") {
                        for ((k, v) in report.overview) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Text(k, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                Text(v, style = MaterialTheme.typography.bodyMedium, color = Gold)
                            }
                        }
                    }
                }
                items(report.sections) { sec ->
                    Section(sec.title, if (sec.note.isBlank()) null else sec.note) {
                        val max = sec.rows.maxOfOrNull { it.value } ?: 1.0
                        for (r in sec.rows) BarRow(r, max)
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

package com.praneethmv.expensetracker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UploadWorker.schedulePeriodic(this)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    val api = remember { SupabaseApi(ctx) }
    var signedIn by remember { mutableStateOf(api.signedIn) }
    if (!signedIn) SignInScreen(api) { signedIn = true; UploadWorker.enqueue(ctx) }
    else HomeScreen(api) { api.signOut(); signedIn = false }
}

@Composable
private fun SignInScreen(api: SupabaseApi, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Expenses", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.padding(8.dp))
        OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.padding(8.dp))
        Button(enabled = !busy && email.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = {
            busy = true; error = null
            scope.launch {
                val err = withContext(Dispatchers.IO) {
                    try { api.signIn(email, password); null } catch (e: Exception) { e.message ?: "Sign-in failed" }
                }
                busy = false
                if (err == null) onDone() else error = err
            }
        }) { Text(if (busy) "Signing in…" else "Sign in") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
    }
}

private fun hasSmsPermission(ctx: Context) =
    listOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS).all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

private val timeFmt = DateTimeFormatter.ofPattern("d MMM, h:mm a").withZone(ZoneId.systemDefault())

@Composable
private fun HomeScreen(api: SupabaseApi, onSignOut: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val queue = remember { LocalQueue.get(ctx) }
    val syncPrefs = remember { ctx.getSharedPreferences("sync", Context.MODE_PRIVATE) }

    var permitted by remember { mutableStateOf(hasSmsPermission(ctx)) }
    var queued by remember { mutableIntStateOf(0) }
    var uploaded by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var txns by remember { mutableStateOf<List<SupabaseApi.Txn>>(emptyList()) }
    var review by remember { mutableStateOf<List<SupabaseApi.Unparsed>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }

    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = hasSmsPermission(ctx)
    }

    fun work(label: String, block: suspend () -> String) {
        busy = true; status = label
        scope.launch {
            status = try { block() } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
            busy = false
            refreshKey++
        }
    }

    LaunchedEffect(refreshKey) {
        val (q, u) = withContext(Dispatchers.IO) { queue.counts() }
        queued = q; uploaded = u
        if (status == null) status = syncPrefs.getString("last_msg", null)
        try {
            val (t, r) = withContext(Dispatchers.IO) { api.recentTransactions() to api.unparsed() }
            txns = t; review = r
        } catch (e: AuthException) {
            onSignOut()
        } catch (e: Exception) {
            status = "Couldn't load: ${e.message}"
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Expenses", style = MaterialTheme.typography.headlineSmall)
                    Text(api.email ?: "", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onSignOut) { Text("Sign out") }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!permitted) {
                        Text("Allow SMS access so bank alerts are captured. Only bank SMS with an amount are uploaded; OTPs and personal messages never leave the phone.")
                        Button(onClick = {
                            askPermission.launch(arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS))
                        }) { Text("Allow SMS access") }
                    } else {
                        Text("Waiting to upload: $queued · Uploaded: $uploaded")
                        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(enabled = !busy, onClick = {
                                work("Importing past SMS…") {
                                    val (found, added) = withContext(Dispatchers.IO) { Backfill.run(ctx, 180) }
                                    "Found $found bank SMS in the last 6 months, $added new. Uploading in background."
                                }
                            }) { Text("Import 6 months") }
                            OutlinedButton(enabled = !busy, onClick = {
                                UploadWorker.enqueue(ctx); status = "Sync started"; refreshKey++
                            }) { Text("Sync") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(enabled = !busy, onClick = { refreshKey++ }) { Text("Refresh") }
                            OutlinedButton(enabled = !busy, onClick = {
                                work("Re-running parser…") {
                                    val n = withContext(Dispatchers.IO) { api.reparseAll() }
                                    "Re-parsed: $n transactions"
                                }
                            }) { Text("Re-parse all") }
                        }
                    }
                }
            }
        }
        if (review.isNotEmpty()) {
            item { SectionTitle("Needs review (${review.size})") }
            items(review) { r ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("${r.sender} · ${fmtTime(r.receivedAt)}", style = MaterialTheme.typography.labelSmall)
                    Text(r.body, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider()
            }
        }
        item { SectionTitle("Recent transactions") }
        if (txns.isEmpty()) item { Text("Nothing yet. Import past SMS or wait for the next bank alert.") }
        items(txns) { t -> TxnRow(t) }
        item { Spacer(Modifier.padding(24.dp)) }
    }
}

@Composable
private fun SectionTitle(s: String) =
    Text(s, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))

@Composable
private fun TxnRow(t: SupabaseApi.Txn) {
    val sign = when (t.kind) { "credit" -> "+"; else -> "" }
    val note = when (t.kind) { "mandate_notice" -> " · upcoming"; "wallet_debit" -> " · FASTag"; else -> "" }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t.merchant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${t.instrument ?: "Unknown card"} · ${fmtTime(t.time)}$note", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(8.dp))
        Text("$sign₹${t.amount}", fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider()
}

private fun fmtTime(iso: String?): String =
    try { if (iso == null) "" else timeFmt.format(Instant.parse(iso.replace(" ", "T").let { normalizeOffset(it) })) }
    catch (e: Exception) { iso ?: "" }

/** PostgREST returns "+00:00" offsets; Instant.parse wants "Z" on older Android. */
private fun normalizeOffset(s: String) = s.replace(Regex("""\+00(:?00)?$"""), "Z")

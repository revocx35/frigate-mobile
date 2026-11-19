package io.github.revocx35.frigatemobile.ui.login

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.revocx35.frigatemobile.core.ServerAddress
import io.github.revocx35.frigatemobile.data.AppGraph
import io.github.revocx35.frigatemobile.data.ServerTrust
import io.github.revocx35.frigatemobile.data.SignInResult
import io.github.revocx35.frigatemobile.ui.LocalNetworkAccess
import io.github.revocx35.frigatemobile.ui.theme.AppIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.cert.X509Certificate
import java.text.DateFormat

/**
 * Sign-in: server address (HTTPS only), Frigate username and password. A self-signed certificate (Frigate's
 * default on port 8971) is shown with its fingerprint and only trusted after the user confirms it.
 */
@Composable
fun LoginScreen(graph: AppGraph, reason: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var server by rememberSaveable { mutableStateOf(graph.accounts.lastServer ?: "") }
    var user by rememberSaveable { mutableStateOf(graph.accounts.lastUser ?: "") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var remember by rememberSaveable { mutableStateOf(true) }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf(reason) }
    var pendingCert by rememberSaveable { mutableStateOf<ByteArray?>(null) }
    var askLan by rememberSaveable { mutableStateOf(false) }
    var lanDenied by rememberSaveable { mutableStateOf(false) }

    fun attempt() {
        error = null
        val address = when (val p = ServerAddress.tryParse(server)) {
            is ServerAddress.ParseResult.Ok -> p.address
            ServerAddress.ParseResult.Cleartext -> {
                error = "Use HTTPS. Frigate serves its login with TLS on port 8971 (e.g. https://192.168.1.10:8971); plain HTTP would expose your password and video."
                return
            }
            ServerAddress.ParseResult.Invalid -> { error = "Enter the server address, e.g. https://192.168.1.10:8971"; return }
        }
        busy = true
        scope.launch(Dispatchers.Main.immediate) {
            if (LocalNetworkAccess.needed(context, address)) {
                busy = false
                askLan = true
                return@launch
            }
            when (val r = graph.signIn(address, user, password, remember)) {
                SignInResult.Ok -> password = ""
                is SignInResult.UntrustedCertificate -> pendingCert = r.chain.first().encoded
                is SignInResult.Failed -> error = r.message
            }
            busy = false
        }
    }

    val lanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        askLan = false
        if (ok) attempt() else lanDenied = true
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))
            Icon(AppIcons.Videocam, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text("Frigate Mobile", style = MaterialTheme.typography.headlineMedium)
            Text("Sign in to your Frigate NVR", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = server, onValueChange = { server = it },
                    label = { Text("Server address") },
                    placeholder = { Text("https://192.168.1.10:8971") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().testTag("server"),
                )
                OutlinedTextField(
                    value = user, onValueChange = { user = it },
                    label = { Text("Username") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().testTag("user"),
                )
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!busy) attempt() }),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) AppIcons.VisibilityOff else AppIcons.Visibility, if (showPassword) "Hide password" else "Show password")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("password"),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it }, modifier = Modifier.testTag("remember"))
                    Column {
                        Text("Stay signed in")
                        Text(
                            "Keeps the password encrypted on this phone to renew the session and check for alerts.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("login_error"))
                }
                Button(onClick = ::attempt, enabled = !busy && server.isNotBlank(), modifier = Modifier.fillMaxWidth().height(48.dp).testTag("sign_in")) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Sign in")
                }
                Text(
                    "Leave username and password empty if your Frigate has authentication turned off.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    pendingCert?.let { bytes ->
        val cert = ServerTrust.parse(bytes)
        if (cert == null) {
            pendingCert = null
        } else {
            CertificateDialog(
                cert,
                onTrust = {
                    graph.trust.pin(cert)
                    pendingCert = null
                    attempt()
                },
                onCancel = { pendingCert = null; error = "The server's certificate was not trusted." },
            )
        }
    }
    if (askLan) {
        AlertDialog(
            onDismissRequest = { askLan = false },
            title = { Text("Allow local network access") },
            text = { Text("This server is on your local network. Android requires your permission before Frigate Mobile can connect to it.") },
            confirmButton = { TextButton(onClick = { lanLauncher.launch(LocalNetworkAccess.PERMISSION) }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { askLan = false }) { Text("Cancel") } },
        )
    }
    if (lanDenied) {
        AlertDialog(
            onDismissRequest = { lanDenied = false },
            title = { Text("Local network access denied") },
            text = { Text(LocalNetworkAccess.DENIED_MESSAGE) },
            confirmButton = { TextButton(onClick = { lanDenied = false; LocalNetworkAccess.openAppSettings(context) }) { Text("Open settings") } },
            dismissButton = { TextButton(onClick = { lanDenied = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CertificateDialog(cert: X509Certificate, onTrust: () -> Unit, onCancel: () -> Unit) {
    val df = DateFormat.getDateInstance(DateFormat.MEDIUM)
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(AppIcons.Shield, null) },
        title = { Text("Trust this certificate?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Android doesn't trust the server's certificate. Frigate creates its own (self-signed) certificate, so this " +
                        "is expected the first time. Compare the fingerprint with your server's before trusting it:",
                )
                Text("Name: ${ServerTrust.commonName(cert)}")
                Text("Valid until: ${df.format(cert.notAfter)}")
                Text("SHA-256:", style = MaterialTheme.typography.labelMedium)
                Text(ServerTrust.sha256(cert), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("fingerprint"))
                Text(
                    "On the server: openssl s_client -connect HOST:8971 </dev/null | openssl x509 -noout -fingerprint -sha256",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onTrust, modifier = Modifier.testTag("trust_cert")) { Text("Trust") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

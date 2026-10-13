package app.fellyjin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import org.jellyfin.sdk.model.api.UserDto
import app.fellyjin.data.JellyfinRepository
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("AuthScreens")

@Composable
private fun CenteredPanel(content: @Composable () -> Unit) {
	Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
		Column(
			Modifier.widthIn(max = 980.dp).fillMaxWidth().padding(ScreenPadding),
			verticalArrangement = Arrangement.spacedBy(28.dp),
		) {
			Column {
				Wordmark(large = true)
				Text(
					"Your Jellyfin library, cinema sized",
					style = MaterialTheme.typography.titleLarge,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			content()
		}
	}
}

@Composable
fun ServerScreen(repository: JellyfinRepository, onConnected: () -> Unit) {
	val scope = rememberCoroutineScope()
	var address by remember { mutableStateOf(repository.serverUrl.orEmpty()) }
	var connecting by remember { mutableStateOf(false) }
	var error by remember { mutableStateOf<String?>(null) }
	val discovered = remember { mutableStateListOf<ServerDiscoveryInfo>() }

	LaunchedEffect(Unit) {
		runCatching {
			repository.discoverLocalServers().collect { server ->
				if (discovered.none { it.id == server.id }) discovered.add(server)
			}
		}.onFailure { logger.warn("Local server discovery failed", it) }
	}

	fun connect(input: String, name: String? = null) {
		if (input.isBlank() || connecting) return
		connecting = true
		error = null
		scope.launch {
			try {
				val server = repository.findServer(input.trim())
				if (server == null) {
					error = "Could not connect to a Jellyfin server at \"$input\""
				} else {
					repository.selectServer(server.address, name ?: server.systemInfo.getOrNull()?.serverName)
					onConnected()
				}
			} catch (err: Exception) {
				logger.warn("Unable to connect to $input", err)
				error = "Could not connect: ${err.message}"
			} finally {
				connecting = false
			}
		}
	}

	CenteredPanel {
		Text("Connect to your server", style = MaterialTheme.typography.headlineLarge)
		OutlinedTextField(
			value = address,
			onValueChange = { address = it },
			label = { Text("Server address") },
			placeholder = { Text("jellyfin.example.com or 192.168.1.10:8096") },
			singleLine = true,
			textStyle = MaterialTheme.typography.titleLarge,
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
			keyboardActions = KeyboardActions(onGo = { connect(address) }),
			modifier = Modifier.fillMaxWidth(),
		)
		error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
		BigButton(if (connecting) "Connecting…" else "Connect", onClick = { connect(address) }, enabled = !connecting)

		if (discovered.isNotEmpty()) {
			Text("Servers on your network", style = MaterialTheme.typography.headlineMedium)
			discovered.forEach { server ->
				FocusCard(onClick = { connect(server.address, server.name) }, modifier = Modifier.fillMaxWidth()) {
					Row(
						Modifier.fillMaxWidth().background(FellyjinColors.Surface).padding(24.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Icon(Icons.Default.Dns, contentDescription = null, modifier = Modifier.size(40.dp), tint = FellyjinColors.Accent)
						Column(Modifier.padding(start = 20.dp)) {
							Text(server.name, style = MaterialTheme.typography.titleLarge)
							Text(server.address, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
						}
					}
				}
			}
		}
	}
}

@Composable
fun LoginScreen(repository: JellyfinRepository, onLoggedIn: () -> Unit, onChangeServer: () -> Unit) {
	val scope = rememberCoroutineScope()
	var username by remember { mutableStateOf("") }
	var password by remember { mutableStateOf("") }
	var error by remember { mutableStateOf<String?>(null) }
	var busy by remember { mutableStateOf(false) }
	var quickConnectCode by remember { mutableStateOf<String?>(null) }
	var quickConnectUnavailable by remember { mutableStateOf(false) }
	var publicUsers by remember { mutableStateOf(emptyList<UserDto>()) }

	LaunchedEffect(Unit) {
		publicUsers = runCatching { repository.publicUsers() }.getOrDefault(emptyList())
	}

	// Quick Connect: no typing in VR, approve the code from the phone or web client
	LaunchedEffect(Unit) {
		val initial = try {
			repository.initiateQuickConnect()
		} catch (err: Exception) {
			logger.info("Quick Connect unavailable", err)
			quickConnectUnavailable = true
			return@LaunchedEffect
		}
		quickConnectCode = initial.code
		while (true) {
			delay(4_000)
			val state = runCatching { repository.quickConnectState(initial.secret) }.getOrNull() ?: continue
			if (state.authenticated) {
				runCatching { repository.completeQuickConnect(initial.secret) }
					.onSuccess { onLoggedIn() }
					.onFailure { error = "Quick Connect failed: ${it.message}" }
				break
			}
		}
	}

	fun login() {
		if (busy || username.isBlank()) return
		busy = true
		error = null
		scope.launch {
			runCatching { repository.login(username.trim(), password) }
				.onSuccess { onLoggedIn() }
				.onFailure {
					logger.warn("Login failed", it)
					error = "Sign in failed, check your username and password"
				}
			busy = false
		}
	}

	CenteredPanel {
		Text("Sign in", style = MaterialTheme.typography.headlineLarge)
		Text(repository.serverUrl.orEmpty(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

		quickConnectCode?.let { code ->
			Column(
				Modifier
					.fillMaxWidth()
					.clip(RoundedCornerShape(24.dp))
					.background(FellyjinColors.Surface.copy(alpha = 0.85f))
					.border(1.dp, FellyjinColors.Outline, RoundedCornerShape(24.dp))
					.padding(32.dp),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Text("Quick Connect", style = MaterialTheme.typography.headlineMedium)
				Text(
					"On your phone or computer open Jellyfin, go to your profile → Quick Connect and enter:",
					style = MaterialTheme.typography.bodyLarge,
				)
				Text(
					code,
					style = MaterialTheme.typography.displayMedium.copy(
						fontSize = 80.sp,
						fontFamily = FontFamily.Monospace,
						letterSpacing = 14.sp,
						brush = FellyjinColors.Brand,
					),
				)
			}
		}
		if (quickConnectUnavailable) {
			Text("Quick Connect is disabled on this server, sign in with your password.", style = MaterialTheme.typography.bodyLarge)
		}

		if (publicUsers.isNotEmpty()) {
			Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
				publicUsers.forEach { user ->
					BigButton(user.name.orEmpty(), onClick = { username = user.name.orEmpty() }, primary = username == user.name)
				}
			}
		}

		OutlinedTextField(
			value = username,
			onValueChange = { username = it },
			label = { Text("Username") },
			singleLine = true,
			textStyle = MaterialTheme.typography.titleLarge,
			keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
			modifier = Modifier.fillMaxWidth(),
		)
		OutlinedTextField(
			value = password,
			onValueChange = { password = it },
			label = { Text("Password") },
			singleLine = true,
			textStyle = MaterialTheme.typography.titleLarge,
			visualTransformation = PasswordVisualTransformation(),
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
			keyboardActions = KeyboardActions(onDone = { login() }),
			modifier = Modifier.fillMaxWidth(),
		)
		error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
		Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
			BigButton(if (busy) "Signing in…" else "Sign in", onClick = ::login, icon = Icons.AutoMirrored.Filled.Login, enabled = !busy)
			BigButton("Change server", onClick = onChangeServer, primary = false)
		}
	}
}

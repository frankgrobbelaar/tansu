package io.github.frankgrobbelaar.tansu.ui.servers

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.frankgrobbelaar.tansu.TansuApplication
import io.github.frankgrobbelaar.tansu.files.FileSystemException
import io.github.frankgrobbelaar.tansu.files.HostKey
import io.github.frankgrobbelaar.tansu.servers.Secret
import io.github.frankgrobbelaar.tansu.servers.SecretLost
import io.github.frankgrobbelaar.tansu.servers.Server
import io.github.frankgrobbelaar.tansu.servers.ServerCheck
import io.github.frankgrobbelaar.tansu.servers.Servers
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the form holds apart from secrets, which never leave memory. */
@Serializable
data class ServerDraft(
    val name: String = "",
    val host: String = "",
    val port: String = "",
    val share: String = "",
    val folder: String = "",
    val username: String = "",
    val signIn: Server.SignIn = Server.SignIn.Password,
    val keyName: String = "",
)

data class ServerFormUiState(
    val kind: Server.Kind,
    val editing: Boolean,
    val draft: ServerDraft,
    val password: String = "",
    val passphrase: String = "",
    val hasKey: Boolean = false,
    val step: Step = Step.Filling,
    val failure: Failure? = null,
    /** The server is saved: the form can close. */
    val done: Boolean = false,
) {
    /** The host to connect to, without an SMB port. */
    val hostName: String
        get() = addressOf(this).first

    val canConnect: Boolean
        get() =
            step == Step.Filling &&
                draft.host.isNotBlank() &&
                (kind == Server.Kind.Sftp || draft.share.isNotBlank()) &&
                (kind == Server.Kind.Smb || portOf(draft.port) != null) &&
                (draft.signIn == Server.SignIn.Guest || draft.username.isNotBlank()) &&
                (draft.signIn != Server.SignIn.Key || hasKey)

    sealed interface Step {
        data object Filling : Step

        data object Connecting : Step

        /**
         * The server at [host] and [port] sent [key]; [replaces] is the key trusted before, if any.
         */
        data class Trust(val key: HostKey, val replaces: String, val host: String, val port: Int) :
            Step
    }

    enum class Failure {
        Unreachable,
        LoginRefused,
        BadKey,
        ShareMissing,
        Locked,
        FolderMissing,
        Unreadable,
        NotAKey,
    }
}

/**
 * Adds a server, or edits one: the fields, a check that they work, the host key to trust, then the
 * save. Nothing is saved until a connection with these details has listed the starting folder.
 */
class ServerFormViewModel(
    kind: Server.Kind,
    private val serverId: String?,
    private val servers: Servers,
    private val check: ServerCheck,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    private val id: String =
        serverId ?: savedState[ID] ?: UUID.randomUUID().toString().also { savedState[ID] = it }
    private val restored: ServerDraft? = savedState.get<String>(DRAFT)?.let(json::decodeFromString)
    private val _state =
        MutableStateFlow(
            ServerFormUiState(
                kind = kind,
                editing = serverId != null,
                draft = restored ?: ServerDraft(port = kind.defaultPort.toString()),
            )
        )
    val state: StateFlow<ServerFormUiState> = _state.asStateFlow()

    private var privateKey = ""
    /** The host key the user trusts, and the host and port it belongs to. */
    private var pinned: Pinned? = null
    private var connecting: Job? = null

    init {
        if (serverId != null) viewModelScope.launch { load(serverId) }
    }

    fun edit(change: (ServerDraft) -> ServerDraft) {
        _state.update { it.copy(draft = change(it.draft), failure = null) }
        savedState[DRAFT] = json.encodeToString(_state.value.draft)
    }

    fun setPassword(password: String) {
        _state.update { it.copy(password = password, failure = null) }
    }

    fun setPassphrase(passphrase: String) {
        _state.update { it.copy(passphrase = passphrase, failure = null) }
    }

    /** Takes the private key read from [name], if it is one. */
    fun useKey(name: String, text: String) {
        if (!looksLikePrivateKey(text)) {
            _state.update { it.copy(failure = ServerFormUiState.Failure.NotAKey) }
            return
        }
        privateKey = text
        _state.update { it.copy(hasKey = true, failure = null) }
        edit { it.copy(keyName = name) }
    }

    /** Checks the details work, then saves the server. */
    fun connect() {
        val form = _state.value
        val server = serverOf(form)
        val secret = secretOf(form)
        connecting?.cancel()
        connecting = viewModelScope.launch {
            _state.update { it.copy(step = ServerFormUiState.Step.Connecting, failure = null) }
            try {
                check.check(server, secret)
                // A check can't be stopped part-way: one that was cancelled may still finish.
                ensureActive()
                servers.save(server, secret)
                _state.update { it.copy(done = true) }
            } catch (e: FileSystemException) {
                ensureActive()
                _state.update { it.withRefusal(e, server) }
            }
        }
    }

    /** Trusts the key the server sent, then connects again with it pinned. */
    fun trust() {
        val trust = _state.value.step as? ServerFormUiState.Step.Trust ?: return
        pinned = Pinned(trust.key.fingerprint, trust.host, trust.port)
        _state.update { it.copy(step = ServerFormUiState.Step.Filling) }
        connect()
    }

    /** Stops connecting, or turns down the key, and goes back to the fields. */
    fun cancel() {
        connecting?.cancel()
        _state.update { it.copy(step = ServerFormUiState.Step.Filling) }
    }

    private suspend fun load(serverId: String) {
        val server = servers.find(serverId) ?: return
        // A secret the phone can no longer open: the login is simply entered again.
        val secret =
            try {
                servers.secret(serverId)
            } catch (_: SecretLost) {
                Secret()
            }
        privateKey = secret.privateKey
        if (server.hostKey.isNotEmpty()) pinned = Pinned(server.hostKey, server.host, server.port)
        _state.update {
            it.copy(
                draft = restored ?: draftOf(server),
                password = secret.password,
                passphrase = secret.passphrase,
                hasKey = secret.privateKey.isNotEmpty(),
            )
        }
    }

    private fun serverOf(form: ServerFormUiState): Server {
        val draft = form.draft
        val (host, port) = addressOf(form)
        val signIn =
            when (form.kind) {
                Server.Kind.Smb -> draft.signIn.takeIf { it == Server.SignIn.Guest }
                Server.Kind.Sftp -> draft.signIn.takeIf { it == Server.SignIn.Key }
            } ?: Server.SignIn.Password
        return Server(
            id = id,
            name = draft.name.trim().ifEmpty { host },
            kind = form.kind,
            host = host,
            port = port,
            share = if (form.kind == Server.Kind.Smb) shareOf(draft) else "",
            folder = folderOf(draft, form.kind),
            username = if (signIn == Server.SignIn.Guest) "" else draft.username.trim(),
            signIn = signIn,
            keyName = if (signIn == Server.SignIn.Key) draft.keyName else "",
            hostKey = pinned?.takeIf { it.host == host && it.port == port }?.fingerprint ?: "",
        )
    }

    private fun secretOf(form: ServerFormUiState): Secret =
        when (serverOf(form).signIn) {
            Server.SignIn.Password -> Secret(password = form.password)
            Server.SignIn.Key -> Secret(privateKey = privateKey, passphrase = form.passphrase)
            Server.SignIn.Guest -> Secret()
        }

    private data class Pinned(val fingerprint: String, val host: String, val port: Int)

    companion object {
        private const val ID = "id"
        private const val DRAFT = "draft"
        private val json = Json { ignoreUnknownKeys = true }

        fun factory(kind: Server.Kind, serverId: String?): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val container = (this[APPLICATION_KEY] as TansuApplication).container
                    ServerFormViewModel(
                        kind = kind,
                        serverId = serverId,
                        servers = container.servers,
                        check = container.serverCheck,
                        savedState = createSavedStateHandle(),
                    )
                }
            }
    }
}

/** The host and port to connect to. SMB's Host field may end in `:port`; SFTP has its own. */
private fun addressOf(form: ServerFormUiState): Pair<String, Int> {
    val host = form.draft.host.trim()
    return when (form.kind) {
        Server.Kind.Smb -> {
            val match = HOST_AND_PORT.matchEntire(host)
            val port = match?.groupValues?.get(2)?.let(::portOf)
            if (match != null && port != null) match.groupValues[1] to port
            else host to form.kind.defaultPort
        }
        Server.Kind.Sftp -> host to (portOf(form.draft.port) ?: form.kind.defaultPort)
    }
}

private val HOST_AND_PORT = Regex("""([^:\[\]]+):(\d+)""")

private const val MAX_PORT = 65_535

/** A port number, if [text] is one. */
internal fun portOf(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..MAX_PORT }

private fun draftOf(server: Server) =
    ServerDraft(
        name = server.name,
        host =
            if (server.kind == Server.Kind.Smb && server.port != server.kind.defaultPort) {
                "${server.host}:${server.port}"
            } else {
                server.host
            },
        port = server.port.toString(),
        share = server.share,
        folder = server.folder,
        username = server.username,
        signIn = server.signIn,
        keyName = server.keyName,
    )

/** The share's own name: Media/Photos is the Media share, starting in Photos. */
private fun shareOf(draft: ServerDraft): String =
    draft.share.trim().trim('/', '\\').split('/', '\\').first()

/** The folder to start in, with anything typed after the share's name in front. */
private fun folderOf(draft: ServerDraft, kind: Server.Kind): String {
    val afterShare =
        if (kind == Server.Kind.Smb) draft.share.trim().trim('/', '\\').split('/', '\\').drop(1)
        else emptyList()
    val folder = draft.folder.trim().trim('/', '\\').replace('\\', '/')
    return (afterShare + listOfNotNull(folder.ifEmpty { null })).joinToString("/")
}

/** OpenSSH, PEM and PuTTY private keys all say what they are on their first line. */
private fun looksLikePrivateKey(text: String): Boolean {
    val start = text.trimStart().take(KEY_HEADER_CHARS)
    return "PRIVATE KEY" in start || start.startsWith("PuTTY-User-Key-File")
}

private const val KEY_HEADER_CHARS = 64

private fun ServerFormUiState.withRefusal(
    e: FileSystemException,
    server: Server,
): ServerFormUiState {
    val filling = copy(step = ServerFormUiState.Step.Filling)
    return when (e) {
        is FileSystemException.HostKeyUnknown ->
            copy(step = ServerFormUiState.Step.Trust(e.key, "", server.host, server.port))
        is FileSystemException.HostKeyChanged ->
            copy(
                step =
                    ServerFormUiState.Step.Trust(e.presented, e.trusted, server.host, server.port)
            )
        is FileSystemException.Unreachable ->
            filling.copy(failure = ServerFormUiState.Failure.Unreachable)
        is FileSystemException.AuthFailed ->
            filling.copy(failure = ServerFormUiState.Failure.LoginRefused)
        is FileSystemException.BadKey -> filling.copy(failure = ServerFormUiState.Failure.BadKey)
        is FileSystemException.ShareMissing ->
            filling.copy(failure = ServerFormUiState.Failure.ShareMissing)
        is FileSystemException.AccessDenied ->
            filling.copy(failure = ServerFormUiState.Failure.Locked)
        is FileSystemException.NotFound ->
            filling.copy(failure = ServerFormUiState.Failure.FolderMissing)
        is FileSystemException.Unreadable,
        is FileSystemException.Exists,
        is FileSystemException.NoSpace ->
            filling.copy(failure = ServerFormUiState.Failure.Unreadable)
    }
}

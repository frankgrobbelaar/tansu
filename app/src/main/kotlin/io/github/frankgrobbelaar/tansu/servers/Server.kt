package io.github.frankgrobbelaar.tansu.servers

import kotlinx.serialization.Serializable

/** An SMB share or SFTP server the user added. Its password or key lives apart, in [Secrets]. */
@Serializable
data class Server(
    val id: String,
    val name: String,
    val kind: Kind,
    val host: String,
    val port: Int,
    /** The SMB share; empty for SFTP. */
    val share: String = "",
    /** Where Browse starts, inside the share or on the server; empty for the top. */
    val folder: String = "",
    val username: String = "",
    val signIn: SignIn = SignIn.Password,
    /** The private key file's name, to show which one is in use. */
    val keyName: String = "",
    /** The SFTP host key fingerprint trusted when the server was added. */
    val hostKey: String = "",
) {
    /** Browse's top folder in this server's drawer. */
    val root: String
        get() = "/" + folder.trim('/')

    enum class Kind(val defaultPort: Int) {
        Smb(445),
        Sftp(22),
    }

    enum class SignIn {
        Password,
        Key,
        Guest,
    }
}

/** What signs tansu in to a server. Only ever stored encrypted. */
@Serializable
data class Secret(
    val password: String = "",
    val privateKey: String = "",
    val passphrase: String = "",
)

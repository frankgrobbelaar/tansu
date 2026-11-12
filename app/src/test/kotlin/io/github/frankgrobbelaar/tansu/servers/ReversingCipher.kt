package io.github.frankgrobbelaar.tansu.servers

/** Seals by reversing the bytes: enough to show the store never holds the plain secret. */
class ReversingCipher : SecretCipher {
    override fun seal(plain: ByteArray) = plain.reversedArray()

    override fun open(sealed: ByteArray) = sealed.reversedArray()
}

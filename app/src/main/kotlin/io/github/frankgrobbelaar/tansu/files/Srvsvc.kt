package io.github.frankgrobbelaar.tansu.files

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Just enough DCE/RPC to ask an SMB server which shares it has: MS-SRVS NetrShareEnum (opnum 15) at
 * level 1, over the server's srvsvc pipe. smbj has no call for it, and it's small enough to write
 * by hand. These are the bytes only, in and out; [SmbFileSystem] sends them down the pipe.
 */
internal object Srvsvc {
    /** The most tansu sends or takes in one fragment: what Windows asks for too. */
    const val MAX_FRAGMENT = 4280

    /** Asks to speak SRVSVC 3.0, in NDR, on the pipe. */
    fun bind(): ByteArray =
        pdu(
            BIND,
            BIND_CALL,
            Writer()
                .u16(MAX_FRAGMENT) // The most tansu sends at once...
                .u16(MAX_FRAGMENT) // ...and takes.
                .u32(0) // A new association.
                .u8(1) // One presentation context,
                .u8(0)
                .u16(0)
                .u16(CONTEXT)
                .u8(1) // with one transfer syntax:
                .u8(0)
                .uuid(SRVSVC) // SRVSVC 3.0...
                .u16(SRVSVC_VERSION)
                .u16(0)
                .uuid(NDR) // ...in NDR.
                .u32(NDR_VERSION)
                .toByteArray(),
        )

    /** NetrShareEnum at level 1, asking for every share at once. */
    fun shareEnum(): ByteArray {
        val stub =
            Writer()
                .u32(NULL) // ServerName: the server asked.
                .u32(LEVEL) // InfoStruct: level 1...
                .u32(LEVEL) // ...its union's arm for level 1...
                .u32(REFERENT) // ...pointing to a level 1 container...
                .u32(0) // ...that's empty.
                .u32(NULL)
                .u32(MAX_PREFERRED_LENGTH) // As much as there is.
                .u32(NULL) // ResumeHandle: from the start.
                .toByteArray()
        return pdu(
            REQUEST,
            ENUM_CALL,
            Writer().u32(stub.size).u16(CONTEXT).u16(NET_SHARE_ENUM).bytes(stub).toByteArray(),
        )
    }

    /** Whether [answer] holds a whole answer, to its last fragment. */
    fun isWhole(answer: ByteArray): Boolean = fragmentsOf(answer) != null

    /** Fails unless [answer] accepts the bind: the server speaks SRVSVC on this pipe. */
    fun checkBound(answer: ByteArray) {
        val ack = fragmentsOf(answer)?.first() ?: throw garbled()
        if (!accepts(ack)) throw ShareEnumFailed("The bind was turned down")
    }

    /**
     * The disk shares named in NetrShareEnum's [answer], by name. IPC$, printers and the special
     * shares Windows makes for itself, such as C$, are left out.
     */
    fun shares(answer: ByteArray): List<String> {
        val read = Reader(stubOf(fragmentsOf(answer) ?: throw garbled()))
        read.u32() // InfoStruct's level...
        read.u32() // ...and its union's arm.
        val shares = if (read.u32() == NULL) emptyList() else containerOf(read)
        read.u32() // TotalEntries.
        if (read.u32() != NULL) read.u32() // ResumeHandle.
        val status = read.u32()
        if (status != 0) throw ShareEnumFailed("NetrShareEnum failed with $status", status)
        return shares.sortedWith(String.CASE_INSENSITIVE_ORDER)
    }

    /** Whether a bind_ack took the one presentation context asked for. */
    private fun accepts(ack: ByteArray): Boolean {
        if (ack[TYPE_AT].toInt() != BIND_ACK) return false
        val read = Reader(ack, at = SECONDARY_ADDRESS_AT)
        read.skip(read.u16()) // The pipe's name for itself.
        read.align(Int.SIZE_BYTES)
        val results = read.u8()
        read.u8()
        read.u16() // Reserved.
        return results > 0 && read.u16() == ACCEPTED
    }

    /** The disk shares in a level 1 container: its array, then each name and remark in turn. */
    private fun containerOf(read: Reader): List<String> {
        read.u32() // EntriesRead.
        if (read.u32() == NULL) return emptyList()
        val count = read.u32()
        if (count !in 0..read.left / SHARE_INFO_1_SIZE) throw garbled()
        val infos = List(count) { ShareInfo(read.u32() != NULL, read.u32(), read.u32() != NULL) }
        return infos.mapNotNull { info ->
            val name = if (info.named) read.text() else null
            if (info.remarked) read.text()
            name?.takeIf { info.isDisk }
        }
    }

    /** One SHARE_INFO_1 as the array holds it: its strings come after the whole array. */
    private class ShareInfo(val named: Boolean, val type: Int, val remarked: Boolean) {
        val isDisk: Boolean
            get() = (type and STYPE_MASK) == STYPE_DISKTREE && (type and STYPE_SPECIAL) == 0
    }

    /**
     * The fragments in [answer], to the last; null while that's still to come. A bind's answer or a
     * fault comes whole, in one.
     */
    private fun fragmentsOf(answer: ByteArray): List<ByteArray>? {
        val numbers = ByteBuffer.wrap(answer).order(ByteOrder.LITTLE_ENDIAN)
        val fragments = mutableListOf<ByteArray>()
        var at = 0
        var whole = false
        while (!whole && answer.size - at >= HEADER_SIZE) {
            val length = numbers.getShort(at + FRAG_LENGTH_AT).toUShort().toInt()
            if (length < HEADER_SIZE) throw garbled()
            if (answer.size - at < length) break
            val fragment = answer.copyOfRange(at, at + length)
            fragments += fragment
            at += length
            whole =
                (fragment[FLAGS_AT].toInt() and LAST_FRAG) != 0 ||
                    fragment[TYPE_AT].toInt() != RESPONSE
        }
        return fragments.takeIf { whole }
    }

    /** The stub the answer's fragments carry between them; a fault fails with its status. */
    private fun stubOf(fragments: List<ByteArray>): ByteArray {
        val stub = ByteArrayOutputStream()
        for (fragment in fragments) {
            when (fragment[TYPE_AT].toInt()) {
                RESPONSE -> stub.write(Reader(fragment, at = STUB_AT).rest())
                FAULT -> {
                    val status = Reader(fragment, at = FAULT_STATUS_AT).u32()
                    throw ShareEnumFailed("The call failed with $status", status)
                }
                else -> throw garbled()
            }
        }
        return stub.toByteArray()
    }

    /** A whole PDU of [type] for call [callId], around [body]. */
    private fun pdu(type: Int, callId: Int, body: ByteArray): ByteArray =
        Writer()
            .u8(RPC_VERSION)
            .u8(0)
            .u8(type)
            .u8(FIRST_FRAG or LAST_FRAG) // All in one fragment.
            .u32(LITTLE_ENDIAN) // Little-endian numbers, ASCII and IEEE floats.
            .u16(HEADER_SIZE + body.size)
            .u16(0) // No authentication of its own: the SMB session is signed in.
            .u32(callId)
            .bytes(body)
            .toByteArray()

    private fun garbled() = ShareEnumFailed("The answer isn't a list of shares")

    private const val RPC_VERSION = 5
    private const val REQUEST = 0
    private const val RESPONSE = 2
    private const val FAULT = 3
    private const val BIND = 11
    private const val BIND_ACK = 12
    private const val FIRST_FRAG = 1
    private const val LAST_FRAG = 2
    private const val LITTLE_ENDIAN = 0x10
    private const val HEADER_SIZE = 16
    private const val TYPE_AT = 2
    private const val FLAGS_AT = 3
    private const val FRAG_LENGTH_AT = 8
    private const val STUB_AT = 24
    private const val FAULT_STATUS_AT = 24
    private const val SECONDARY_ADDRESS_AT = 24
    private const val ACCEPTED = 0
    private const val BIND_CALL = 1
    private const val ENUM_CALL = 2
    private const val CONTEXT = 0
    private const val SRVSVC = "4b324fc8-1670-01d3-1278-5a47bf6ee188"
    private const val SRVSVC_VERSION = 3
    private const val NDR = "8a885d04-1ceb-11c9-9fe8-08002b104860"
    private const val NDR_VERSION = 2
    private const val NET_SHARE_ENUM = 15
    private const val LEVEL = 1
    private const val NULL = 0
    private const val REFERENT = 0x00020000
    private const val MAX_PREFERRED_LENGTH = -1
    private const val SHARE_INFO_1_SIZE = 3 * Int.SIZE_BYTES
    private const val STYPE_DISKTREE = 0
    private const val STYPE_MASK = 0x0FFFFFFF
    private const val STYPE_SPECIAL = 1 shl 31
}

/**
 * The server didn't list its shares: it turned the call down, with a [status] when it gave one, or
 * its answer made no sense.
 */
internal class ShareEnumFailed(message: String, val status: Int = 0) : Exception(message) {
    /** This login may not ask. */
    val denied: Boolean
        get() = status == ACCESS_DENIED

    private companion object {
        const val ACCESS_DENIED = 5
    }
}

/** Little-endian numbers and bytes, written in order, as DCE/RPC and NDR lay them out. */
private class Writer {
    private val out = ByteArrayOutputStream()

    fun u8(value: Int) = apply { out.write(value) }

    fun u16(value: Int) = u8(value).u8(value shr Byte.SIZE_BITS)

    fun u32(value: Int) = u16(value).u16(value shr Short.SIZE_BITS)

    fun bytes(value: ByteArray) = apply { out.write(value) }

    /**
     * A UUID as DCE/RPC writes it: its first three parts little-endian, its last eight bytes as is.
     */
    fun uuid(text: String): Writer {
        val uuid = UUID.fromString(text)
        val high = uuid.mostSignificantBits
        return u32((high ushr Int.SIZE_BITS).toInt())
            .u16((high ushr Short.SIZE_BITS).toInt())
            .u16(high.toInt())
            .bytes(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(uuid.leastSignificantBits).array())
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

/**
 * NDR's little-endian numbers, read in order from [at] in [bytes], each aligned to its size from
 * the start. Bytes that run out make a garbled answer, not a crash.
 */
private class Reader(private val bytes: ByteArray, private var at: Int = 0) {
    val left: Int
        get() = bytes.size - at

    fun u8(): Int = number(Byte.SIZE_BYTES)

    fun u16(): Int = number(Short.SIZE_BYTES)

    fun u32(): Int = number(Int.SIZE_BYTES)

    fun skip(count: Int) {
        take(count)
    }

    fun align(size: Int) = skip((-at).mod(size))

    /** Everything from here to the end. */
    fun rest(): ByteArray {
        val count = left
        val start = take(count)
        return bytes.copyOfRange(start, start + count)
    }

    /** A `[string] wchar_t*`'s characters: its counts, then UTF-16 up to the NUL that ends it. */
    fun text(): String {
        u32() // The most it could hold...
        u32() // ...from where...
        val chars = u32() // ...and how many are here, the NUL too.
        if (chars !in 0..left / Char.SIZE_BYTES) throw ShareEnumFailed("A name runs off the end")
        val size = chars * Char.SIZE_BYTES
        return String(bytes, take(size), size, Charsets.UTF_16LE).substringBefore(NUL)
    }

    private fun number(size: Int): Int {
        align(size)
        val start = take(size)
        var value = 0
        for (index in start + size - 1 downTo start) {
            value = value shl Byte.SIZE_BITS or bytes[index].toUByte().toInt()
        }
        return value
    }

    /** Moves past the next [count] bytes, saying where they start. */
    private fun take(count: Int): Int {
        if (count !in 0..left) throw ShareEnumFailed("The answer ends too soon")
        return at.also { at += count }
    }

    private companion object {
        const val NUL = '\u0000'
    }
}

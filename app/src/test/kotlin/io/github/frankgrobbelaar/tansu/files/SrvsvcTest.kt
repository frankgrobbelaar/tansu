package io.github.frankgrobbelaar.tansu.files

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SrvsvcTest {
    @Test
    fun `the bind asks for SRVSVC in NDR`() {
        assertEquals(
            hex(
                "05 00 0b 03 10 00 00 00 48 00 00 00 01 00 00 00", // A bind of 72 bytes, call 1.
                "b8 10 b8 10 00 00 00 00", // Fragments of 4280 bytes at most; a new association.
                "01 00 00 00 00 00 01 00", // One context, numbered 0, with one transfer syntax.
                "c8 4f 32 4b 70 16 d3 01 12 78 5a 47 bf 6e e1 88 03 00 00 00", // SRVSVC 3.0.
                "04 5d 88 8a eb 1c c9 11 9f e8 08 00 2b 10 48 60 02 00 00 00", // NDR 2.
            ),
            Srvsvc.bind().toHex(),
        )
    }

    @Test
    fun `NetrShareEnum asks for every share at level 1`() {
        assertEquals(
            hex(
                "05 00 00 03 10 00 00 00 38 00 00 00 02 00 00 00", // A request of 56 bytes, call 2.
                "20 00 00 00 00 00 0f 00", // 32 bytes of stub, context 0, opnum 15.
                "00 00 00 00", // ServerName: NULL.
                "01 00 00 00 01 00 00 00 00 00 02 00", // Level 1, its arm, the container.
                "00 00 00 00 00 00 00 00", // The container: nothing read, no buffer.
                "ff ff ff ff", // PreferedMaximumLength: all of them.
                "00 00 00 00", // ResumeHandle: NULL.
            ),
            Srvsvc.shareEnum().toHex(),
        )
    }

    @Test
    fun `a bind counts only when the server takes it`() {
        Srvsvc.checkBound(bindAck(result = 0))

        assertThrows(ShareEnumFailed::class.java) { Srvsvc.checkBound(bindAck(result = 2)) }
        val nak = pdu(BIND_NAK, WHOLE, Ndr().u16(0).u8(1).u8(5).u8(0).bytes())
        assertThrows(ShareEnumFailed::class.java) { Srvsvc.checkBound(nak) }
    }

    @Test
    fun `the disk shares come out by name, from an answer in two fragments`() {
        val stub = shareList()
        // The stub is split part-way through a number, as fragments may split it.
        val split = stub.size / 2 + 1
        val first = response(stub.copyOfRange(0, split), FIRST_FRAG)
        val last = response(stub.copyOfRange(split, stub.size), LAST_FRAG)

        assertFalse(Srvsvc.isWhole(ByteArray(0)))
        assertFalse(Srvsvc.isWhole(first))
        assertFalse(Srvsvc.isWhole(first + last.copyOf(HALF_A_HEADER)))
        assertFalse(Srvsvc.isWhole(first + last.copyOf(last.size - 1)))
        assertTrue(Srvsvc.isWhole(first + last))

        // IPC$, C$ and the printer are left out; the share with no remark stays.
        assertEquals(listOf("archive", "Backups", "Media 2026"), Srvsvc.shares(first + last))
    }

    @Test
    fun `a server that won't list its shares says so`() {
        val fault = pdu(FAULT, WHOLE, Ndr().u32(0).u16(0).u8(0).u8(0).u32(DENIED).u32(0).bytes())
        assertTrue(assertThrows(ShareEnumFailed::class.java) { Srvsvc.shares(fault) }.denied)

        // NetrShareEnum's own answer: no container, no entries, and access denied.
        val refused = Ndr().u32(1).u32(1).u32(0).u32(0).u32(0).u32(DENIED).bytes()
        val failed =
            assertThrows(ShareEnumFailed::class.java) {
                Srvsvc.shares(response(refused, WHOLE))
            }
        assertTrue(failed.denied)
    }

    @Test
    fun `an answer that makes no sense fails, rather than crashing`() {
        val stub = shareList()
        // Cut short, and claiming far more shares than it could hold.
        val cut = response(stub.copyOf(stub.size / 2), WHOLE)
        val boastful = Ndr().u32(1).u32(1).u32(REF).u32(BILLION).u32(REF).u32(BILLION).bytes()
        val nonsense = pdu(BIND, WHOLE, ByteArray(8))

        for (answer in listOf(cut, response(boastful, WHOLE), nonsense)) {
            val failed = assertThrows(ShareEnumFailed::class.java) { Srvsvc.shares(answer) }
            assertFalse(failed.denied)
        }
    }

    /**
     * NetrShareEnum's answer as Samba or Windows writes it: the level 1 container, its array, then
     * every name and remark, then the totals and the status.
     */
    private fun shareList(): ByteArray =
        Ndr()
            .u32(1) // Level 1...
            .u32(1) // ...its arm...
            .u32(REF) // ...and the container,
            .u32(SHARES)
            .u32(REF + 1) // holding six shares,
            .u32(SHARES) // in an array of six:
            .share(name = true, type = DISK, remark = true) // Media 2026
            .share(name = true, type = SPECIAL or IPC, remark = true) // IPC$
            .share(name = true, type = DISK, remark = false) // Backups
            .share(name = true, type = SPECIAL or DISK, remark = true) // C$
            .share(name = true, type = PRINTER, remark = true) // Laser
            .share(name = true, type = TEMPORARY or DISK, remark = true) // archive
            .text("Media 2026")
            .text("Photos and films")
            .text("IPC$")
            .text("IPC Service (Samba)")
            .text("Backups")
            .text("C$")
            .text("Default share")
            .text("Laser")
            .text("The hall printer")
            .text("archive")
            .text("Old things")
            .u32(SHARES) // TotalEntries.
            .u32(REF + 2) // ResumeHandle...
            .u32(0)
            .u32(0) // ...and done.
            .bytes()

    private fun Ndr.share(name: Boolean, type: Int, remark: Boolean) =
        u32(if (name) REF else 0).u32(type).u32(if (remark) REF else 0)

    /** A bind_ack as Samba sends it, its one result [result]: 0 took the bind. */
    private fun bindAck(result: Int): ByteArray {
        val pipe = "\\PIPE\\srvsvc\u0000".toByteArray(Charsets.US_ASCII)
        val body =
            Ndr()
                .u16(Srvsvc.MAX_FRAGMENT)
                .u16(Srvsvc.MAX_FRAGMENT)
                .u32(0x53f0)
                .u16(pipe.size)
                .raw(pipe)
                .align(Int.SIZE_BYTES)
                .u8(1) // One result:
                .u8(0)
                .u16(0)
                .u16(result)
                .u16(0)
                .raw(NDR_SYNTAX)
                .u32(2)
                .bytes()
        return pdu(BIND_ACK, WHOLE, body)
    }

    private fun response(stub: ByteArray, flags: Int) =
        pdu(RESPONSE, flags, Ndr().u32(stub.size).u16(0).u8(0).u8(0).raw(stub).bytes())

    /** A PDU as a server sends it: the common header, then [body]. */
    private fun pdu(type: Int, flags: Int, body: ByteArray) =
        Ndr()
            .u8(5)
            .u8(0)
            .u8(type)
            .u8(flags)
            .u32(0x10)
            .u16(HEADER + body.size)
            .u16(0)
            .u32(2)
            .raw(body)
            .bytes()

    /** NDR as a server writes it: little-endian numbers, each aligned to its size. */
    private class Ndr {
        private val out = ByteArrayOutputStream()

        fun u8(value: Int) = apply { out.write(value) }

        fun u16(value: Int) = align(2).u8(value).u8(value shr 8)

        fun u32(value: Int) = align(4).u16(value).u16(value shr 16)

        fun text(value: String): Ndr {
            val chars = value.length + 1
            return u32(chars).u32(0).u32(chars).raw("$value\u0000".toByteArray(Charsets.UTF_16LE))
        }

        fun raw(value: ByteArray) = apply { out.write(value) }

        fun align(size: Int) = apply { while (out.size() % size != 0) out.write(0) }

        fun bytes(): ByteArray = out.toByteArray()
    }

    private fun hex(vararg lines: String) = lines.joinToString(" ")

    private fun ByteArray.toHex() = joinToString(" ") { "%02x".format(it) }

    private companion object {
        const val RESPONSE = 2
        const val FAULT = 3
        const val BIND = 11
        const val BIND_ACK = 12
        const val BIND_NAK = 13
        const val FIRST_FRAG = 1
        const val LAST_FRAG = 2
        const val WHOLE = FIRST_FRAG or LAST_FRAG
        const val HEADER = 16
        const val HALF_A_HEADER = 8
        const val REF = 0x00020000
        const val SHARES = 6
        const val DISK = 0
        const val PRINTER = 1
        const val IPC = 3
        const val TEMPORARY = 0x40000000
        const val SPECIAL = 1 shl 31
        const val DENIED = 5
        const val BILLION = 1_000_000_000
        val NDR_SYNTAX =
            "04 5d 88 8a eb 1c c9 11 9f e8 08 00 2b 10 48 60"
                .split(' ')
                .map { it.toInt(16).toByte() }
                .toByteArray()
    }
}

package io.github.frankgrobbelaar.tansu.ui.servers

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalNetworkTest {
    @Test
    fun `home network addresses and names look local, Tailscale and the internet don't`() {
        val local =
            listOf(
                "192.168.1.5",
                "10.0.0.2",
                "172.20.1.1",
                "169.254.3.4",
                "nas",
                "nas.local",
                "fe80::1",
            )
        val elsewhere =
            listOf("100.101.102.103", "nas.tail1234.ts.net", "example.com", "172.32.0.1", "8.8.8.8")

        assertEquals(local, local.filter(LocalNetwork::looksLocal))
        assertEquals(emptyList<String>(), elsewhere.filter(LocalNetwork::looksLocal))
    }
}

package dev.unrabbit.core

import org.junit.Assert.*
import org.junit.Test

class ConnectionSettingsTest {
    @Test fun connectionDraftAcceptsOwnHostsAndRejectsAdminOrMalformedInputs() {
        assertNull(ConnectionSettings("vm.example.net", 2222, "unrabbit").validationError())
        assertNull(ConnectionSettings("2001:db8::1", 22, "unrabbit").validationError())
        assertNotNull(ConnectionSettings("https://vm.example.net", 22, "unrabbit").validationError())
        assertNotNull(ConnectionSettings("vm.example.net", 22, "root").validationError())
        assertNotNull(ConnectionSettings("vm.example.net", 0, "unrabbit").validationError())
        assertNotNull(ConnectionSettings("vm\n.example.net", 22, "unrabbit").validationError())
    }
}

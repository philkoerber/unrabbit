package dev.unrabbit.app

enum class VmConnectionState(val description: String) {
    UNPAIRED("VM nicht gekoppelt"),
    CHECKING("VM wird geprüft"),
    CONNECTED("VM verbunden"),
    DISCONNECTED("VM nicht erreichbar"),
}

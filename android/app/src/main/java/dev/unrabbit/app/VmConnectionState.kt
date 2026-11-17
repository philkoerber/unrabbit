package dev.unrabbit.app

enum class VmConnectionState(val description: String) {
    UNPAIRED("VM not paired"),
    CHECKING("Checking VM"),
    CONNECTED("VM connected"),
    DISCONNECTED("VM unreachable"),
}

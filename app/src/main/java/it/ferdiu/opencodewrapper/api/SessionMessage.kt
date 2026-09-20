package it.ferdiu.opencodewrapper.api

/** The last human-readable message of a session, flattened for TTS/readout. */
data class SessionMessage(
    val isFromUser: Boolean,
    val text: String,
)

package it.ferdiu.opencodewrapper.api

/** A reply to a pending permission request. [apiValue] is the wire value the
 *  server expects in the reply body's reply field (verified against live
 *  server v1.18.31). */
enum class PermissionDecision(val apiValue: String) {
    ONCE("once"),
    ALWAYS("always"),
    REJECT("reject"),
}

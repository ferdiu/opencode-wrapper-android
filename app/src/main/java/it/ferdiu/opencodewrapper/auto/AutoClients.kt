package it.ferdiu.opencodewrapper.auto

import androidx.car.app.CarContext
import it.ferdiu.opencodewrapper.api.ApiVersionDetector
import it.ferdiu.opencodewrapper.api.OpenCodeClient
import it.ferdiu.opencodewrapper.data.ServerConfigStore

/** Builds an API client from the stored server config (probing the API
 *  generation once per base URL), or null when the server was never
 *  configured on the phone. Null on [it.ferdiu.opencodewrapper.api.ApiVersionException]
 *  too: a strict-v2 profile pointed at a non-v2 server has no usable client;
 *  screens already treat null as an error state. */
suspend fun CarContext.openCodeClientOrNull(): OpenCodeClient? =
    ServerConfigStore(applicationContext).get()?.let { config ->
        runCatching { ApiVersionDetector.clientFor(config) }.getOrNull()
    }

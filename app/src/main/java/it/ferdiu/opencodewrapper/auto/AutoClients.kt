package it.ferdiu.opencodewrapper.auto

import androidx.car.app.CarContext
import it.ferdiu.opencodewrapper.api.OpenCodeClientV2
import it.ferdiu.opencodewrapper.data.ServerConfigStore

/** Builds an API client from the stored server config, or null when the
 *  server was never configured on the phone. */
fun CarContext.openCodeClientOrNull(): OpenCodeClientV2? =
    ServerConfigStore(applicationContext).get()?.let { OpenCodeClientV2(it) }

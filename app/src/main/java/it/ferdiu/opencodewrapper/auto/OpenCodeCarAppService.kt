package it.ferdiu.opencodewrapper.auto

import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import it.ferdiu.opencodewrapper.R

class OpenCodeCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            // Release: allow the Android Auto host (phone projection) from the
            // allow-list; AAOS system hosts pass via their privileged
            // TEMPLATE_RENDERER permission. Everything else is rejected.
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(R.array.hosts_allowlist)
                .build()
        }

    override fun onCreateSession(): Session = OpenCodeCarSession()
}

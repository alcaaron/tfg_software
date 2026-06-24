package com.punchthrough.blestarterappandroid

import android.app.Application
import android.content.Context

class A3MeshApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base, LocaleHelper.getSavedLocale(base)))
    }
}

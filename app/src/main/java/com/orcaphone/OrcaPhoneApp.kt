package com.orcaphone

import android.app.Application
import com.orcaphone.data.Repositories

class OrcaPhoneApp : Application() {
    lateinit var repos: Repositories
        private set

    override fun onCreate() {
        super.onCreate()
        repos = Repositories(this)
    }
}

package io.github.foliba.phorca

import android.app.Application
import io.github.foliba.phorca.data.Repositories

class PhorcaApp : Application() {
    lateinit var repos: Repositories
        private set

    override fun onCreate() {
        super.onCreate()
        repos = Repositories(this)
    }
}

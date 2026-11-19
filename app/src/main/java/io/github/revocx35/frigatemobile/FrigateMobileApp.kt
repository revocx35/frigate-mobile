package io.github.revocx35.frigatemobile

import android.app.Application
import io.github.revocx35.frigatemobile.data.AlertWorker
import io.github.revocx35.frigatemobile.data.AppGraph

open class FrigateMobileApp : Application() {
    lateinit var graph: AppGraph
        private set

    /** Tests override this (e.g. with a cipher that doesn't need the Android Keystore). */
    protected open fun createGraph(): AppGraph = AppGraph(this)

    override fun onCreate() {
        super.onCreate()
        graph = createGraph()
        AlertWorker.createChannel(this)
    }
}

package dev.dejalango.app

import android.app.Application
import android.content.Context
import dev.dejalango.app.di.AppGraph

class DejaLangoApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

val Context.appGraph: AppGraph
    get() = (applicationContext as DejaLangoApp).graph

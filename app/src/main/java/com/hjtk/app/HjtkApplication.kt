package com.hjtk.app

import android.app.Application
import com.hjtk.app.android.Engine

/**
 * Process entry point. The engine is built lazily on first use, so cold start
 * only pays for what the first screen needs.
 */
open class HjtkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Touch the engine once so stores are loaded before the first frame and
        // a corrupt file is repaired early rather than mid-conversation.
        Engine.of(this)
    }
}

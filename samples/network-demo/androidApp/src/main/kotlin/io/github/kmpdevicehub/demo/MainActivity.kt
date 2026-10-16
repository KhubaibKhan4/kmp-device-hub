package io.github.kmpdevicehub.demo

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.runBlocking

/** Runs the demo calls and shows what the app received; each line also goes to Logcat (tag DEMO). */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = TextView(this).apply { setPadding(32, 96, 32, 32); textSize = 14f; text = "Network demo\n" }
        setContentView(ScrollView(this).apply { addView(text) })
        Thread {
            runBlocking {
                DemoCalls.run { line ->
                    Log.i("DEMO", line)
                    runOnUiThread { text.append("\n$line") }
                }
            }
        }.start()
    }
}

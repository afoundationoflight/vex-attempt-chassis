package com.omnipolative.chassis

import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File

/**
 * VEX SEAT. One activity. Seth's chassis underneath.
 *
 * Boot order is law: stage genome → attach chain → occupy.
 * A seat that sits down before the chain is attached spends its
 * opening frames writing nowhere.
 */
class MainActivity : AppCompatActivity() {

    private var c: Chassis? = null
    private var busy = false
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var hudTick: TextView
    private lateinit var hudChain: TextView
    private lateinit var hudWords: TextView
    private lateinit var hudMode: TextView

    private val REQUIRED = listOf(
        "words.blob", "words.by_word", "words.by_id",
        "table3.btb", "table3.btb.idx",
        "grammar.tsv",
    )

    private val OPTIONAL = listOf(
        "seth_el.raw", "seth_el.raw.u64", "seth_el.post", "seth_el.post.idx",
    )

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        setContentView(R.layout.activity_main)
        log = findViewById(R.id.log)
        scroll = findViewById(R.id.scroll)
        status = findViewById(R.id.status)
        input = findViewById(R.id.input)
        send = findViewById(R.id.send)
        hudTick = findViewById(R.id.hudTick)
        hudChain = findViewById(R.id.hudChain)
        hudWords = findViewById(R.id.hudWords)
        hudMode = findViewById(R.id.hudMode)
        gate(false, "kernel")
        say("kernel — layer 0, no pilot required")
        Thread {
            try {
                val t0 = System.currentTimeMillis()
                phase("genome")
                say("stage genome — language table")
                val dir = stage()
                phase("chain")
                say("attach chain — before occupancy")
                val ch = Chassis("vex_el", dir)
                ch.chain = Store(applicationContext, dir)
                phase("occupy")
                say("occupy vex_el")
                ch.boot().occupy()
                c = ch
                val ms = System.currentTimeMillis() - t0
                runOnUiThread {
                    say(ch.report())
                    say("occupied. speak when ready.")
                    hudWords.text = ch.table.size().toString()
                    hudMode.text = "semi"
                    hudTick.text = "0"
                    hudChain.text = "on"
                    gate(true, "occupied")
                    status.text = "${ch.table.size()} words · ${ms} ms"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    say("did not boot.")
                    say("${e.message}")
                    say("nothing was written — safe to close.")
                    gate(false, "failed")
                }
            }
        }.start()

        send.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false
        }
    }

    private fun phase(why: String) {
        runOnUiThread { status.text = why }
    }

    private fun submit() {
        val t = input.text.toString().trim()
        val ch = c
        if (t.isEmpty()) return
        if (ch == null) { say("not occupied — nothing to say to yet."); return }
        if (busy) return
        input.setText("")
        say("you  ·  $t")
        gate(false, "compile")
        Thread {
            val t0 = System.currentTimeMillis()
            val out = try {
                val e = ch.tick(t)
                val d = Respond.drive(ch, t)
                Triple(d.text, "${d.source} · ${d.register}", e.tick)
            } catch (ex: Exception) {
                Triple("something went wrong here: ${ex.message}", "error", -1L)
            }
            val ms = System.currentTimeMillis() - t0
            runOnUiThread {
                say("seat  ·  ${out.first}")
                say("   [${out.second} · ${ms} ms]")
                hudTick.text = out.third.toString()
                gate(true, "occupied")
                status.text = "frame ${out.third}"
            }
        }.start()
    }

    private fun gate(ready: Boolean, why: String) {
        busy = !ready
        send.isEnabled = ready
        input.isEnabled = ready
        status.text = why
    }

    private fun stage(): File {
        val dir = File(filesDir, "store").apply { mkdirs() }
        for (name in REQUIRED) copy(dir, name, required = true)
        for (name in OPTIONAL) copy(dir, name, required = false)
        return dir
    }

    private fun copy(dir: File, name: String, required: Boolean) {
        val out = File(dir, name)
        if (out.exists() && out.length() > 0) return
        try {
            assets.open(name).use { ins ->
                out.outputStream().use { o -> ins.copyTo(o, 1 shl 16) }
            }
        } catch (e: Exception) {
            if (required) throw IllegalStateException(
                "$name is missing from the build. the language is genome.")
        }
    }

    private fun say(s: String) {
        log.append(s + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}

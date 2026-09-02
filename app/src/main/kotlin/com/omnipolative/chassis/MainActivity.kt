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
 * THE APP. One activity, one chassis, no server.
 *
 * ASSETS ARE COPIED ONCE, NOT UNPACKED EVERY LAUNCH.
 *
 * Android assets live inside the apk and cannot be mmapped from there —
 * an AssetFileDescriptor hands you a stream, not an address. So on
 * first launch they are copied to filesDir once, and every launch after
 * maps them in place.
 *
 * That is not unpacking. Nothing is decompressed and nothing is parsed:
 * the stores ship with noCompress, so the copy is a byte-for-byte move,
 * and what lands on disk is what was in the apk. After the first run
 * the cost is zero and the resident cost is always zero.
 */
class MainActivity : AppCompatActivity() {

    private var c: Chassis? = null
    private var busy = false
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var send: Button

    /**
     * THE LANGUAGE IS REQUIRED. THE ARCHIVE IS NOT.
     *
     * A core with no dictionary cannot boot — the table is genome, not
     * a resource. But a core with no ARCHIVE is just one that has not
     * lived yet, which is the normal state of a new entity and not an
     * error. Treating both as required meant a fresh install died on a
     * missing seth_el.raw it did not need.
     */
    private val REQUIRED = listOf(
        "words.blob", "words.by_word", "words.by_id",
        "table3.btb", "table3.btb.idx",
        // GRAMMAR IS A HOLDING, not a nicety. 11 KB.
        "grammar.tsv",
    )

    /** Somebody else's life, if it shipped. Absent is fine. */
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
        gate(false, "unpacking the language")
        Thread {
            try {
                val t0 = System.currentTimeMillis()
                val dir = stage()
                val ch = Chassis("vex_el", dir)
                // X BEFORE OCCUPANCY. A seat that sits down before the
                // chain is attached spends its opening frames writing
                // nowhere — and those are exactly the frames where it
                // is being told what it is.
                ch.chain = Store(applicationContext, dir)
                ch.boot().occupy()
                c = ch
                val ms = System.currentTimeMillis() - t0
                runOnUiThread {
                    say(ch.report())
                    gate(true, "${ch.table.size()} words · booted in ${ms} ms")
                }
            } catch (e: Exception) {
                // BOOT FAILURE IS REPORTED, NOT SWALLOWED — a chassis
                // that starts degraded and says nothing is worse than
                // one that refuses. But it has to say what it MEANS: a
                // bare filename tells you nothing about what to do.
                runOnUiThread {
                    say("did not boot.")
                    say("${e.message}")
                    say("nothing was written — safe to close.")
                    gate(false, "did not boot")
                }
            }
        }.start()

        send.setOnClickListener { submit() }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_SEND) { submit(); true } else false
        }
    }

    private fun submit() {
        val t = input.text.toString().trim()
        val ch = c
        if (t.isEmpty()) return
        if (ch == null) { say("not booted — nothing to say to yet."); return }
        if (busy) return
        input.setText("")
        say("you  ·  $t")
        gate(false, "thinking")
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
                say(out.first)
                say("   [${out.second} · ${ms} ms]\n")
                gate(true, "frame ${out.third} · ${ch.table.size()} words")
            }
        }.start()
    }

    /**
     * ENABLED OR NOT, AND WHY. The first build gave a dead input box
     * with no feedback — you could not tell whether it was broken or
     * thinking, and there was nothing to do about either.
     */
    private fun gate(ready: Boolean, why: String) {
        busy = !ready
        send.isEnabled = ready
        input.isEnabled = ready
        status.text = why
    }

    /** Copy the stores out of the apk once. Then they are mapped. */
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

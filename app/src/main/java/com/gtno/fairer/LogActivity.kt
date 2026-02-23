package com.gtno.fairer

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.LinearLayout.LayoutParams.MATCH_PARENT
import android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import com.gtno.fairer.data.BlockLog
import com.gtno.fairer.databinding.ActivityLogBinding

class LogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogBinding
    private var jostLight: Typeface? = null
    private var jostRegular: Typeface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        jostLight   = ResourcesCompat.getFont(this, R.font.jost_light)
        jostRegular = ResourcesCompat.getFont(this, R.font.jost_regular)

        binding.backButton.setOnClickListener { finish() }
        binding.clearButton.setOnClickListener {
            BlockLog.clear()
            renderLog()
        }

        renderLog()
    }

    override fun onResume() {
        super.onResume()
        renderLog()
    }

    // ── Rendering ──────────────────────────────────────────────────────────────

    private fun renderLog() {
        val container = binding.logContainer
        container.removeAllViews()

        val events = BlockLog.getAll()
        if (events.isEmpty()) {
            addEmptyState(container)
            return
        }

        // Flat list: group by domain+category, sort by most-recent block first
        data class Entry(val domain: String, val category: String, val count: Int, val lastSeen: Long)

        val entries = events
            .groupBy { it.domain to it.category }
            .map { (key, evts) ->
                Entry(
                    domain   = key.first,
                    category = key.second,
                    count    = evts.size,
                    lastSeen = evts.maxOf { it.timestamp },
                )
            }
            .sortedByDescending { it.lastSeen }

        entries.forEachIndexed { index, entry ->
            if (index > 0) addDivider(container)
            addDomainEntry(container, entry.domain, entry.category, entry.count)
        }
    }

    // ── View builders ──────────────────────────────────────────────────────────

    private fun addEmptyState(container: LinearLayout) {
        TextView(this).apply {
            text = "no blocked requests this session"
            typeface = jostRegular
            textSize = 14f
            setTextColor(Color.argb(166, 255, 255, 255))
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(40), dp(24), dp(40))
            container.addView(this, MATCH_PARENT, WRAP_CONTENT)
        }
    }

    private fun addDivider(container: LinearLayout) {
        val view = android.view.View(this).apply {
            setBackgroundColor(Color.argb(30, 255, 255, 255))
        }
        val lp = LinearLayout.LayoutParams(MATCH_PARENT, dp(1)).apply {
            setMargins(dp(24), dp(10), dp(24), 0)
        }
        container.addView(view, lp)
    }

    private fun addDomainEntry(
        container: LinearLayout,
        domain: String,
        category: String,
        count: Int,
    ) {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(24), dp(4), dp(24), dp(4))

        // Row 1: domain name + block count
        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        row1.gravity = Gravity.CENTER_VERTICAL

        val domainView = TextView(this)
        domainView.text = domain
        domainView.typeface = jostLight
        domainView.textSize = 15f
        domainView.setTextColor(Color.WHITE)
        domainView.maxLines = 1
        domainView.ellipsize = TextUtils.TruncateAt.END
        row1.addView(domainView, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val countView = TextView(this)
        countView.text = "×$count"
        countView.typeface = jostLight
        countView.textSize = 15f
        countView.setTextColor(Color.WHITE)
        row1.addView(countView, WRAP_CONTENT, WRAP_CONTENT)

        card.addView(row1, MATCH_PARENT, WRAP_CONTENT)

        // Row 2: category label
        val catView = TextView(this)
        catView.text = category
        catView.typeface = jostRegular
        catView.textSize = 10f
        catView.setTextColor(Color.argb(140, 255, 255, 255))
        catView.isAllCaps = true
        catView.letterSpacing = 0.1f
        catView.setPadding(0, dp(2), 0, 0)
        card.addView(catView, MATCH_PARENT, WRAP_CONTENT)

        container.addView(card, MATCH_PARENT, WRAP_CONTENT)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}

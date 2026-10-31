package net.machang.backup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shared structural kit for the four top-level pages （备份 / 文件库 / 记录 / 我的）.
 *
 * FUNCTIONAL SCAFFOLD ONLY — styling is intentionally plain and neutral
 * (system-default buttons/fields, no brand colors, no custom drawables).
 * A separate design pass will define the visual skin and reskin these
 * pages; the helper signatures here are the seams that pass will restyle,
 * so pages should build exclusively through them.
 *
 * Pages are separate activities in singleTop mode; the nav bar reorders
 * the target to the front instead of stacking duplicates.
 */
object Ui {

    // Neutral grays + semantic status colors (functional, not branding).
    val TEXT = Color.rgb(0x21, 0x21, 0x21)
    val TEXT_SUB = Color.rgb(0x66, 0x66, 0x66)
    val HAIRLINE = Color.rgb(0xCC, 0xCC, 0xCC)
    val OK = Color.rgb(0x1E, 0x7A, 0x34)
    val WARN = Color.rgb(0xB0, 0x70, 0x00)
    val BAD = Color.rgb(0xB0, 0x30, 0x30)
    val INFO = Color.rgb(0x2D, 0x5D, 0xB0)

    enum class Page(val label: String, val activity: Class<out Activity>) {
        HOME("备份", MainActivity::class.java),
        LIBRARY("文件库", LibraryActivity::class.java),
        RECORDS("记录", RecordsActivity::class.java),
        ACCOUNT("我的", AccountActivity::class.java)
    }

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------ scaffold

    class PageRoot(val root: LinearLayout, val content: LinearLayout)

    /** Page root: [PageRoot.content] fills the space above the bottom nav bar. */
    fun page(act: Activity, current: Page): PageRoot {
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            content,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        root.addView(navBar(act, current))
        act.setContentView(root)
        return PageRoot(root, content)
    }

    fun navBar(act: Activity, current: Page): View {
        val wrap = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        wrap.addView(View(act).apply { setBackgroundColor(HAIRLINE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 1)))
        val bar = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        for (page in Page.values()) {
            val selected = page == current
            val item = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(act, 10), 0, dp(act, 10))
                isClickable = true
                if (!selected) {
                    setOnClickListener {
                        act.startActivity(
                            Intent(act, page.activity)
                                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        )
                    }
                }
            }
            item.addView(TextView(act).apply {
                text = page.label
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(if (selected) TEXT else TEXT_SUB)
                if (selected) setTypeface(typeface, Typeface.BOLD)
            })
            bar.addView(
                item,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        wrap.addView(bar)
        return wrap
    }

    // -------------------------------------------------------------- pieces

    /** Plain page header: app name + page title (+ optional subtitle). */
    fun header(act: Activity, title: String, subtitle: String? = null): LinearLayout {
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(act, 16), dp(act, 12), dp(act, 16), dp(act, 4))
        }
        col.addView(TextView(act).apply {
            text = "牛备 · $title"
            textSize = 19f
            setTypeface(typeface, Typeface.BOLD)
        })
        if (subtitle != null) {
            col.addView(TextView(act).apply {
                text = subtitle
                textSize = 12.5f
                setTextColor(TEXT_SUB)
                setPadding(0, dp(act, 2), 0, 0)
            })
        }
        return col
    }

    /**
     * A content section with margins and padding. Plain for now — the
     * design pass turns this into a card.
     */
    fun cardBlock(act: Activity, build: LinearLayout.() -> Unit): LinearLayout {
        val outer = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(act, 16), dp(act, 10), dp(act, 16), dp(act, 10))
        }
        outer.build()
        return outer
    }

    fun sectionTitle(act: Activity, text: String): TextView = TextView(act).apply {
        this.text = text
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(act, 6))
    }

    fun bodyText(act: Activity, text: String = ""): TextView = TextView(act).apply {
        this.text = text
        textSize = 14f
        setTextColor(TEXT)
    }

    fun subText(act: Activity, text: String = ""): TextView = TextView(act).apply {
        this.text = text
        textSize = 12.5f
        setTextColor(TEXT_SUB)
    }

    fun primaryButton(act: Activity, text: String): Button = Button(act).apply {
        this.text = text
        textSize = 15f
        setAllCaps(false)
    }

    fun accentButton(act: Activity, text: String): Button = Button(act).apply {
        this.text = text
        textSize = 15f
        setAllCaps(false)
    }

    fun ghostButton(act: Activity, text: String): Button = Button(act).apply {
        this.text = text
        textSize = 14f
        setAllCaps(false)
    }

    fun field(act: Activity, hintText: String, password: Boolean = false): EditText =
        EditText(act).apply {
            hint = hintText
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or
                (if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_TEXT_VARIATION_NORMAL)
        }

    /** Small status label; the color is semantic (status), set as plain text color. */
    fun chip(act: Activity, text: String, color: Int): TextView = TextView(act).apply {
        this.text = text
        textSize = 11f
        setTextColor(color)
        setTypeface(typeface, Typeface.BOLD)
    }

    fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    fun spaced(lp: LinearLayout.LayoutParams, topDp: Int, ctx: Context): LinearLayout.LayoutParams =
        lp.apply { topMargin = dp(ctx, topDp) }
}

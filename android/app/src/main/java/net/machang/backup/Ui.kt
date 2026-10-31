package net.machang.backup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Shared UI kit for the four top-level pages （备份 / 文件库 / 记录 / 我的）
 * plus the immersive viewer screens — skinned to the user-approved design
 * mockup (artifact `niubackup`, v1.1). All tokens below are the mockup's
 * :root values verbatim; pages build exclusively through these helpers so
 * the skin stays consistent.
 *
 * Palette: deep teal-blue brand scale, scarce 牛橙 accent (main action /
 * pending only), four product-wide status colors. Manrope is not bundled;
 * numbers use the platform sans (tabular figures where the platform has
 * them) — layout, colors, radii and spacing follow the mockup exactly.
 */
object Ui {

    // ------------------------------------------------------------- tokens
    val BRAND_900 = Color.rgb(0x06, 0x30, 0x3B)
    val BRAND_800 = Color.rgb(0x0A, 0x47, 0x57)
    val BRAND_700 = Color.rgb(0x0B, 0x55, 0x63)
    val BRAND_600 = Color.rgb(0x0E, 0x6B, 0x7E)
    val BRAND_500 = Color.rgb(0x12, 0x80, 0x9A)
    val BRAND_100 = Color.rgb(0xD9, 0xED, 0xF1)
    val BRAND_50 = Color.rgb(0xEB, 0xF5, 0xF7)

    val ACCENT = Color.rgb(0xF0, 0x7F, 0x13)
    val ACCENT_600 = Color.rgb(0xDE, 0x6A, 0x08)
    val ACCENT_100 = Color.rgb(0xFD, 0xEB, 0xD3)

    val INK = Color.rgb(0x16, 0x24, 0x2C)
    val INK_2 = Color.rgb(0x41, 0x54, 0x5F)
    val SLATE = Color.rgb(0x64, 0x79, 0x8A)
    val SLATE_BG = Color.rgb(0xE7, 0xED, 0xF0)
    val LINE = Color.rgb(0xDC, 0xE6, 0xEA)
    val MIST = Color.rgb(0xEE, 0xF4, 0xF5)
    val SCREEN_BG = Color.rgb(0xF3, 0xF7, 0xF8)
    val PAPER = Color.WHITE
    val NAV_INACTIVE = Color.rgb(0x84, 0x96, 0xA1)

    // Status pairs (bg / text) — product-wide semantics (design §状态色）.
    val OK = Color.rgb(0x1E, 0x9E, 0x66)
    val OK_BG = Color.rgb(0xE1, 0xF4, 0xEA)
    val OK_TEXT = Color.rgb(0x0E, 0x70, 0x46)
    val RUN = Color.rgb(0x24, 0x70, 0xD8)
    val RUN_BG = Color.rgb(0xE2, 0xED, 0xFC)
    val RUN_TEXT = Color.rgb(0x17, 0x4E, 0x9E)
    val WAIT = Color.rgb(0xC7, 0x74, 0x00)
    val WAIT_BG = Color.rgb(0xFB, 0xEE, 0xD2)
    val WAIT_TEXT = Color.rgb(0x8A, 0x52, 0x00)
    val PC_TEXT = Color.rgb(0x43, 0x58, 0x6A)
    val NONE_BG = Color.rgb(0xEF, 0xF2, 0xF3)
    val NONE_TEXT = Color.rgb(0x71, 0x82, 0x8D)

    val LOGOUT_BG = Color.rgb(0xF9, 0xEC, 0xE9)
    val LOGOUT_TEXT = Color.rgb(0xB3, 0x40, 0x2E)

    // Immersive (player / photo preview) dark surfaces.
    val VIEW_BG = Color.rgb(0x0C, 0x1A, 0x21)
    val PHOTO_BG = Color.rgb(0x0A, 0x16, 0x1C)
    val VIEW_TEXT = Color.rgb(0xEA, 0xF3, 0xF5)
    val VIEW_SUB = Color.rgb(0x9D, 0xB8, 0xC1)

    // Legacy aliases used across pages.
    val TEXT = INK
    val TEXT_SUB = SLATE
    val HAIRLINE = LINE
    val WARN = WAIT
    val BAD = LOGOUT_TEXT
    val INFO = RUN

    const val R_CARD = 16
    const val R_BTN = 12
    const val R_BIG = 14
    const val R_TILE = 10
    const val R_BADGE = 6
    const val R_THUMB = 9

    // Top-chrome geometry, shared by every page so tab switches never
    // shift the header or the first content element (top alignment pass):
    // the app bar's content height is FIXED at 8dp top pad + the 28dp
    // brand row + 10dp bottom pad, and every page's first content element
    // starts exactly PAGE_TOP below the bar.
    const val BAR_HEIGHT = 46
    const val BAR_ROW = 28
    const val PAGE_TOP = 12

    enum class StatusKind(val bg: Int, val text: Int, val dot: Int) {
        OK(OK_BG, OK_TEXT, Color.rgb(0x1E, 0x9E, 0x66)),
        RUN(RUN_BG, RUN_TEXT, Color.rgb(0x24, 0x70, 0xD8)),
        WAIT(WAIT_BG, WAIT_TEXT, ACCENT),
        PC(SLATE_BG, PC_TEXT, SLATE),
        NONE(NONE_BG, NONE_TEXT, NONE_TEXT)
    }

    enum class Page(val label: String, val activity: Class<out Activity>) {
        HOME("备份", MainActivity::class.java),
        LIBRARY("文件库", LibraryActivity::class.java),
        RECORDS("记录", RecordsActivity::class.java),
        ACCOUNT("我的", AccountActivity::class.java)
    }

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun font(weight: Int): Typeface = when {
        weight >= 800 -> Typeface.create("sans-serif", Typeface.BOLD)
        weight >= 600 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        else -> Typeface.create("sans-serif", Typeface.NORMAL)
    }

    // ------------------------------------------------------------ shapes

    fun rounded(
        ctx: Context, color: Int, radiusDp: Int,
        strokeColor: Int? = null, strokeDp: Int = 1
    ): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(ctx, radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(ctx, strokeDp), strokeColor)
    }

    fun pillDrawable(ctx: Context, color: Int, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(ctx, 999).toFloat()
            if (strokeColor != null) setStroke(dp(ctx, 1), strokeColor)
        }

    fun circleDrawable(ctx: Context, color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    /** Soft card shadow (mockup --shadow-card approximation via elevation). */
    fun elevateCard(ctx: Context, view: View) {
        view.elevation = dp(ctx, 3).toFloat()
    }

    fun statusBar(act: Activity, color: Int, lightIcons: Boolean = false) {
        act.window.statusBarColor = color
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var vis = act.window.decorView.systemUiVisibility
            vis = if (lightIcons) {
                vis and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                vis or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
            act.window.decorView.systemUiVisibility = vis
        }
    }

    // ------------------------------------------------------------ scaffold

    class PageRoot(
        val root: LinearLayout,
        val headerBar: View,
        val headerRight: LinearLayout,
        val content: LinearLayout
    )

    /**
     * Page scaffold: a FIXED app bar (see [appBar]) above
     * [PageRoot.content], the bottom nav bar below it. The app bar is part
     * of the scaffold, never of the scrolling body, so it cannot scroll
     * away (MainActivity/AccountActivity bodies scroll under it).
     *
     * Edge-to-edge (targetSdk 35 draws behind the system bars): on API 30+
     * the scaffold opts out of decor-fitting and grows the app bar by the
     * status-bar inset — its padding stays constant, so the brand row
     * keeps its exact [BAR_HEIGHT] geometry and simply extends through
     * the status area (BRAND_800, white icons) — and pads the nav bar by
     * the navigation-bar inset so it clears the gesture bar. Below API 30
     * the framework still fits the window itself and the BRAND_800 status
     * bar color carries the same look.
     */
    fun page(act: Activity, current: Page, title: String): PageRoot {
        statusBar(act, BRAND_800, lightIcons = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            act.window.setDecorFitsSystemWindows(false)
        }
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SCREEN_BG)
        }
        val (bar, right) = appBar(act, title)
        root.addView(bar)
        val content = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            content,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )
        val nav = navBar(act, current)
        root.addView(nav)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                // Grow the bar by the status inset instead of re-padding
                // it: the brand row's inner geometry never changes, so
                // the header is pixel-identical on every page.
                val lp = bar.layoutParams as LinearLayout.LayoutParams
                lp.height = dp(act, BAR_HEIGHT) + bars.top
                bar.layoutParams = lp
                nav.setPadding(0, 0, 0, bars.bottom)
                insets
            }
        }
        act.setContentView(root)
        return PageRoot(root, bar, right, content)
    }

    /**
     * The fixed app bar (approved mockup header): brand tile + 牛备
     * wordmark on the left, the page title next to it, and an empty right
     * slot ([PageRoot.headerRight]) for the page's status pill / action.
     * BRAND_800 so it flows seamlessly from the same-colored status area.
     *
     * The bar's height is FIXED at [BAR_HEIGHT] and every element of the
     * brand row (tile, wordmark, title, right slot) is exactly [BAR_ROW]
     * tall, so nothing a page puts in the right slot can grow or shrink
     * the header, and the wordmark/title text boxes share the tile's
     * center line on every page — tab switches are pixel-stable.
     */
    fun appBar(act: Activity, title: String): Pair<LinearLayout, LinearLayout> {
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BRAND_800)
            setPadding(dp(act, 16), dp(act, 8), dp(act, 16), dp(act, 10))
            elevation = dp(act, 2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, BAR_HEIGHT)
            )
        }
        bar.addView(brandTile(act, BAR_ROW))
        bar.addView(
            TextView(act).apply {
                text = "牛备"
                textSize = 16f
                typeface = font(900)
                setTextColor(Color.WHITE)
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(act, 8), 0, 0, 0)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(act, BAR_ROW)
            )
        )
        bar.addView(
            TextView(act).apply {
                text = title
                textSize = 15f
                typeface = font(600)
                setTextColor(BRAND_100)
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(act, 10), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, dp(act, BAR_ROW), 1f)
        )
        val right = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        bar.addView(
            right,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(act, BAR_ROW)
            )
        )
        return bar to right
    }

    fun navBar(act: Activity, current: Page): View {
        val wrap = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(PAPER)
        }
        wrap.addView(
            View(act).apply { setBackgroundColor(LINE) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 1))
        )
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(act, 6), 0, dp(act, 8))
        }
        for (page in Page.values()) {
            val selected = page == current
            val tint = if (selected) BRAND_600 else NAV_INACTIVE
            val item = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                if (!selected) {
                    setOnClickListener {
                        // Tab hops are instant: no slide/fade in either
                        // direction (FLAG_ACTIVITY_NO_ANIMATION +
                        // overridePendingTransition on the caller; the
                        // matching finish() overrides live in the four
                        // tab activities for the back-press path).
                        act.startActivity(
                            Intent(act, page.activity)
                                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                                .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        )
                        @Suppress("DEPRECATION")
                        act.overridePendingTransition(0, 0)
                    }
                }
            }
            item.addView(NavIconView(act, page, tint))
            item.addView(TextView(act).apply {
                text = page.label
                textSize = 10.5f
                gravity = Gravity.CENTER
                setTextColor(tint)
                typeface = font(if (selected) 700 else 500)
                setPadding(0, dp(act, 2), 0, 0)
            })
            // Active tab: 4dp accent dot under the label (mockup tabbar).
            val dot = View(act).apply {
                background = circleDrawable(act, if (selected) ACCENT else Color.TRANSPARENT)
            }
            item.addView(
                dot,
                LinearLayout.LayoutParams(dp(act, 4), dp(act, 4)).apply {
                    topMargin = dp(act, 2)
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            )
            bar.addView(
                item,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
        }
        wrap.addView(bar)
        return wrap
    }

    // -------------------------------------------------------------- pieces

    /**
     * A white card (radius 16, mockup shadow, 14dp inner padding) with 16dp
     * side margins and a [PAGE_TOP] top gap — the same gap above every
     * card, so a page's first card starts at the same offset everywhere.
     * Add it WITHOUT layout params so its margins apply.
     */
    fun cardBlock(act: Activity, build: LinearLayout.() -> Unit): LinearLayout {
        val card = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(act, PAPER, R_CARD)
            setPadding(dp(act, 14), dp(act, 14), dp(act, 14), dp(act, 14))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                leftMargin = dp(act, 16)
                rightMargin = dp(act, 16)
                topMargin = dp(act, PAGE_TOP)
            }
        }
        elevateCard(act, card)
        card.build()
        return card
    }

    /** Card-section header: 12sp/700 slate, slight letter spacing. */
    fun sectionTitle(act: Activity, text: String): TextView = TextView(act).apply {
        this.text = text
        textSize = 12f
        setTextColor(SLATE)
        typeface = font(700)
        letterSpacing = 0.02f
        setPadding(0, 0, 0, dp(act, 8))
    }

    fun bodyText(act: Activity, text: String = ""): TextView = TextView(act).apply {
        this.text = text
        textSize = 14f
        setTextColor(INK)
        setLineSpacing(0f, 1.45f)
    }

    fun subText(act: Activity, text: String = ""): TextView = TextView(act).apply {
        this.text = text
        textSize = 12f
        setTextColor(SLATE)
        setLineSpacing(0f, 1.45f)
    }

    private fun baseButton(act: Activity, text: String): Button = Button(act).apply {
        this.text = text
        setAllCaps(false)
        typeface = font(700)
        stateListAnimator = null
    }

    /** Primary action: brand-600 fill, radius 12, 44dp touch height. */
    fun primaryButton(act: Activity, text: String): Button = baseButton(act, text).apply {
        textSize = 14f
        setTextColor(Color.WHITE)
        background = rounded(act, BRAND_600, R_BTN)
        minHeight = dp(act, 44)
        setPadding(dp(act, 18), dp(act, 11), dp(act, 18), dp(act, 11))
    }

    /** The scarce orange CTA (main action / selection-mode release). */
    fun accentButton(act: Activity, text: String): Button = baseButton(act, text).apply {
        textSize = 14f
        setTextColor(Color.WHITE)
        background = rounded(act, ACCENT, R_BTN)
        minHeight = dp(act, 44)
        setPadding(dp(act, 18), dp(act, 11), dp(act, 18), dp(act, 11))
    }

    /** Full-width hero action （立即备份）: radius 14, 16sp. */
    fun bigButton(act: Activity, text: String): Button = baseButton(act, text).apply {
        textSize = 16f
        setTextColor(Color.WHITE)
        background = rounded(act, BRAND_600, R_BIG)
        setPadding(dp(act, 18), dp(act, 14), dp(act, 18), dp(act, 14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = dp(act, 16)
            rightMargin = dp(act, 16)
            topMargin = dp(act, PAGE_TOP)
        }
    }

    fun ghostButton(act: Activity, text: String): Button = baseButton(act, text).apply {
        textSize = 13f
        setTextColor(BRAND_700)
        background = rounded(act, MIST, R_BTN)
        minHeight = dp(act, 44)
        setPadding(dp(act, 14), dp(act, 9), dp(act, 14), dp(act, 9))
    }

    /** Small ghost button (btn-xs in the mockup: 11.5sp, radius 8). */
    fun xsButton(act: Activity, text: String): Button = baseButton(act, text).apply {
        textSize = 11.5f
        setTextColor(BRAND_700)
        background = rounded(act, MIST, 8)
        minWidth = 0
        minimumWidth = 0
        // No platform minimum height: an xs button must fit the app bar's
        // fixed 28dp right slot without stretching the header.
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(act, 10), dp(act, 5), dp(act, 10), dp(act, 5))
    }

    fun field(act: Activity, hintText: String, password: Boolean = false): EditText =
        EditText(act).apply {
            hint = hintText
            textSize = 14f
            setTextColor(INK)
            setHintTextColor(NAV_INACTIVE)
            background = rounded(act, PAPER, R_BTN, LINE, 1)
            minHeight = dp(act, 48)
            setPadding(dp(act, 12), dp(act, 11), dp(act, 12), dp(act, 11))
            inputType = InputType.TYPE_CLASS_TEXT or
                (if (password) InputType.TYPE_TEXT_VARIATION_PASSWORD
                else InputType.TYPE_TEXT_VARIATION_NORMAL)
        }

    /** Status badge: 10.5sp/700 on the status bg, radius 6. */
    fun badge(act: Activity, text: String, kind: StatusKind): TextView = TextView(act).apply {
        this.text = text
        textSize = 10.5f
        typeface = font(700)
        setTextColor(kind.text)
        background = rounded(act, kind.bg, R_BADGE)
        setPadding(dp(act, 7), dp(act, 4), dp(act, 7), dp(act, 4))
    }

    /** Status pill: 7dp colored dot + 11.5sp/700 label, full-round bg. */
    fun pill(act: Activity, text: String, kind: StatusKind): LinearLayout {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = pillDrawable(act, kind.bg)
            setPadding(dp(act, 10), dp(act, 5), dp(act, 10), dp(act, 5))
        }
        row.addView(
            View(act).apply { background = circleDrawable(act, kind.dot) },
            LinearLayout.LayoutParams(dp(act, 7), dp(act, 7)).apply {
                rightMargin = dp(act, 6)
            }
        )
        row.addView(TextView(act).apply {
            this.text = text
            textSize = 11.5f
            typeface = font(700)
            setTextColor(kind.text)
        })
        return row
    }

    /** Update a pill built by [pill] in place (dot + label). */
    fun setPill(pillRow: LinearLayout, text: String, kind: StatusKind) {
        val ctx = pillRow.context
        pillRow.background = pillDrawable(ctx, kind.bg)
        (pillRow.getChildAt(0) as? View)?.background = circleDrawable(ctx, kind.dot)
        (pillRow.getChildAt(1) as? TextView)?.apply {
            this.text = text
            setTextColor(kind.text)
        }
    }

    /** Filter chip （全部/照片/视频/未备份）: white+line border, on = brand fill. */
    fun filterChip(act: Activity, text: String, on: Boolean): TextView = TextView(act).apply {
        this.text = text
        textSize = 11.5f
        typeface = font(600)
        gravity = Gravity.CENTER
        isClickable = true
        if (on) {
            setTextColor(Color.WHITE)
            background = pillDrawable(act, BRAND_600, BRAND_600)
        } else {
            setTextColor(INK_2)
            background = pillDrawable(act, PAPER, LINE)
        }
        setPadding(dp(act, 11), dp(act, 5), dp(act, 11), dp(act, 5))
    }

    /** Key/value row: key 12.5 ink-2 left, value 700 right-aligned. */
    fun kvRow(act: Activity, key: String, value: View): LinearLayout {
        val row = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(act, 5), 0, dp(act, 5))
        }
        row.addView(TextView(act).apply {
            text = key
            textSize = 12.5f
            setTextColor(INK_2)
        })
        row.addView(
            value,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { leftMargin = dp(act, 12) }
        )
        return row
    }

    fun kvValue(act: Activity, text: String): TextView = TextView(act).apply {
        this.text = text
        textSize = 12.5f
        typeface = font(700)
        setTextColor(INK)
        gravity = Gravity.END
        setSingleLine(true)
        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
    }

    /** Horizontal progress track (8dp, radius 99) with a colored fill. */
    class ProgressBarView(ctx: Context, fillColor: Int) : FrameLayout(ctx) {
        private val fill: View = View(ctx).apply {
            background = pillDrawable(ctx, fillColor)
        }

        init {
            background = pillDrawable(ctx, SLATE_BG)
            addView(fill, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        fun setPercent(pct: Int) {
            val p = pct.coerceIn(0, 100)
            post {
                val w = (width * p / 100)
                fill.layoutParams = LayoutParams(
                    if (p == 0) 0 else maxOf(w, Ui.dp(context, 8)),
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
    }

    fun progressBar(act: Activity, fillColor: Int): ProgressBarView =
        ProgressBarView(act, fillColor).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 8)
            )
        }

    fun hairline(act: Activity): View = View(act).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(act, 1)
        ).apply {
            topMargin = dp(act, 8)
            bottomMargin = dp(act, 8)
        }
    }

    /** Brand wordmark tile: teal gradient rounded square with 牛 + amber tick. */
    fun brandTile(act: Activity, sizeDp: Int): View {
        val tile = GradientDrawable().apply {
            orientation = GradientDrawable.Orientation.TL_BR
            colors = intArrayOf(BRAND_500, BRAND_800)
            cornerRadius = dp(act, sizeDp * 26 / 100).toFloat()
        }
        val wrap = FrameLayout(act).apply {
            background = tile
            elevation = dp(act, 2).toFloat()
        }
        wrap.addView(TextView(act).apply {
            text = "牛"
            setTextColor(Color.WHITE)
            typeface = font(900)
            textSize = sizeDp * 0.52f
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        // Amber backup-arrow accent at the forehead position of the glyph.
        wrap.addView(View(act).apply {
            background = rounded(act, ACCENT, 2)
            rotation = 45f
        }, FrameLayout.LayoutParams(dp(act, sizeDp / 6), dp(act, sizeDp / 6)).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(act, sizeDp / 10)
        })
        wrap.layoutParams = LinearLayout.LayoutParams(dp(act, sizeDp), dp(act, sizeDp))
        return wrap
    }

    fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    )

    fun spaced(lp: LinearLayout.LayoutParams, topDp: Int, ctx: Context): LinearLayout.LayoutParams =
        lp.apply { topMargin = dp(ctx, topDp) }
}

/** Tiny line-icon view for the bottom navigation (21dp box, 1.8dp stroke). */
class NavIconView(ctx: Context, private val page: Ui.Page, private val tint: Int) : View(ctx) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        style = Paint.Style.STROKE
        strokeWidth = Ui.dp(ctx, 2).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        style = Paint.Style.FILL
    }

    init {
        layoutParams = LinearLayout.LayoutParams(Ui.dp(ctx, 21), Ui.dp(ctx, 21))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2f
        val path = Path()
        when (page) {
            Ui.Page.HOME -> {
                // Upload arrow into a tray.
                canvas.drawLine(cx, h * 0.62f, cx, h * 0.16f, paint)
                path.moveTo(cx - w * 0.20f, h * 0.34f)
                path.lineTo(cx, h * 0.14f)
                path.lineTo(cx + w * 0.20f, h * 0.34f)
                canvas.drawPath(path, paint)
                path.reset()
                path.moveTo(w * 0.14f, h * 0.66f)
                path.lineTo(w * 0.14f, h * 0.84f)
                path.lineTo(w * 0.86f, h * 0.84f)
                path.lineTo(w * 0.86f, h * 0.66f)
                canvas.drawPath(path, paint)
            }
            Ui.Page.LIBRARY -> {
                // 2x2 grid of rounded squares.
                val s = w * 0.34f
                val g = w * 0.10f
                val x0 = (w - 2 * s - g) / 2f
                val y0 = (h - 2 * s - g) / 2f
                for (r in 0..1) for (c in 0..1) {
                    canvas.drawRoundRect(
                        RectF(x0 + c * (s + g), y0 + r * (s + g), x0 + c * (s + g) + s, y0 + r * (s + g) + s),
                        w * 0.05f, w * 0.05f, paint
                    )
                }
            }
            Ui.Page.RECORDS -> {
                // List lines with dots.
                for (i in 0..2) {
                    val y = h * (0.26f + i * 0.24f)
                    canvas.drawCircle(w * 0.18f, y, w * 0.045f, fillPaint)
                    canvas.drawLine(w * 0.34f, y, w * 0.86f, y, paint)
                }
            }
            Ui.Page.ACCOUNT -> {
                // Person: head circle + shoulders arc.
                canvas.drawCircle(cx, h * 0.34f, w * 0.17f, paint)
                path.addArc(
                    RectF(w * 0.16f, h * 0.58f, w * 0.84f, h * 1.14f), 200f, 140f
                )
                canvas.drawPath(path, paint)
            }
        }
    }
}

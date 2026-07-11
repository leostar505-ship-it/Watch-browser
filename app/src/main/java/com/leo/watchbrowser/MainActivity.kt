package com.leo.watchbrowser

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import androidx.core.view.InputDeviceCompat
import androidx.core.view.MotionEventCompat
import androidx.core.view.ViewConfigurationCompat
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import kotlin.math.abs

class MainActivity : Activity() {

    private lateinit var geckoView: GeckoView
    private lateinit var geckoSession: GeckoSession
    private lateinit var runtime: GeckoRuntime
    private lateinit var backButton: ImageButton
    private lateinit var refreshButton: ImageButton
    private lateinit var zoomButton: ImageButton
    private lateinit var zoomPanel: LinearLayout
    private lateinit var zoomSeekBar: SeekBar
    private lateinit var rootLayout: FrameLayout
    private val startUrl = "https://www.google.com"

    private var scrollFactor: Float = 64f
    private var canGoBack: Boolean = false
    private var inputOverlay: FrameLayout? = null

    // Current page zoom, driven by the zoom slider (50-150 -> 0.50-1.50).
    // Persisted here so it carries over to the next page load, same as
    // picking a zoom level in a normal browser.
    private var currentZoom: Float = 0.78f

    // Edge-swipe-to-go-back tracking (see dispatchTouchEvent below).
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L
    private var edgeSwipeThresholdPx = 0f
    private var edgeSwipeMinDistancePx = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rootLayout = findViewById(android.R.id.content)
        geckoView = findViewById(R.id.geckoview)
        backButton = findViewById(R.id.backButton)
        refreshButton = findViewById(R.id.refreshButton)
        zoomButton = findViewById(R.id.zoomButton)
        zoomPanel = findViewById(R.id.zoomPanel)
        zoomSeekBar = findViewById(R.id.zoomSeekBar)

        val density = resources.displayMetrics.density
        edgeSwipeThresholdPx = 24 * density
        edgeSwipeMinDistancePx = 60 * density

        runtime = GeckoRuntime.getDefault(this)

        geckoSession = GeckoSession()
        geckoSession.open(runtime)
        geckoView.setSession(geckoSession)

        // Track back-history so we know whether back should navigate
        // pages or fall through to closing the app.
        geckoSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onCanGoBack(session: GeckoSession, canGoBackNow: Boolean) {
                canGoBack = canGoBackNow
            }
        }

        // Watch screens are tiny (~450px), so normal mobile-sized pages render
        // too large to be usable. Force a wider viewport and scale it back
        // down once each page finishes loading, similar to "desktop site + zoom out".
        // Skipped while a text field is focused so it doesn't interrupt typing.
        // Uses currentZoom (adjustable via the zoom slider) instead of a
        // fixed value so the chosen zoom level carries over between pages.
        geckoSession.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                if (success) {
                    applyZoom()
                }
            }
        }

        // GeckoView's own IME bridge doesn't visually render a keyboard on
        // this watch even though the OS reports it as shown, so bypass it
        // entirely: show a custom full-screen input overlay (which does
        // trigger the real keyboard, same as any other app) and push the
        // typed text back into the page's currently-focused field via JS.
        geckoSession.textInput.setDelegate(object : GeckoSession.TextInputDelegate {
            override fun showSoftInput(session: GeckoSession) {
                showInputOverlay()
            }

            override fun hideSoftInput(session: GeckoSession) {
                dismissInputOverlay()
            }
        })

        geckoSession.loadUri(startUrl)

        backButton.setOnClickListener { handleBackAction() }

        refreshButton.setOnClickListener {
            hideZoomPanel()
            geckoSession.reload()
        }

        zoomButton.setOnClickListener {
            if (zoomPanel.visibility == View.VISIBLE) hideZoomPanel() else showZoomPanel()
        }

        zoomSeekBar.progress = (currentZoom * 100).toInt()
        zoomSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                // Clamp so it can't be dragged down to 0 (unusably tiny) or
                // stupidly large.
                val clamped = progress.coerceIn(30, 150)
                currentZoom = clamped / 100f
                applyZoom()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        scrollFactor = try {
            ViewConfigurationCompat.getScaledVerticalScrollFactor(
                ViewConfiguration.get(this), this
            )
        } catch (e: Exception) {
            64f
        }

        geckoView.isFocusable = true
        geckoView.isFocusableInTouchMode = true
        geckoView.requestFocus()

        geckoView.setOnGenericMotionListener { _, event ->
            if (event.action == MotionEvent.ACTION_SCROLL &&
                event.source and InputDeviceCompat.SOURCE_ROTARY_ENCODER != 0
            ) {
                val delta = -event.getAxisValue(MotionEventCompat.AXIS_SCROLL) * scrollFactor
                geckoSession.loadUri(
                    "javascript:window.scrollBy(0, ${delta.toInt()});"
                )
                true
            } else {
                false
            }
        }
    }

    // Injects the current zoom level into the loaded page. Called on every
    // page load (via onPageStop) and live whenever the slider moves.
    private fun applyZoom() {
        geckoSession.loadUri(
            "javascript:(function(){" +
                "var active=document.activeElement;" +
                "var tag=active?active.tagName:'';" +
                "if(tag==='INPUT'||tag==='TEXTAREA'||(active&&active.isContentEditable)){return;}" +
                "var m=document.querySelector('meta[name=viewport]');" +
                "if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}" +
                "m.content='width=480, initial-scale=1';" +
                "document.body.style.zoom='$currentZoom';" +
                "})();"
        )
    }

    private fun showZoomPanel() {
        zoomPanel.visibility = View.VISIBLE
    }

    private fun hideZoomPanel() {
        zoomPanel.visibility = View.GONE
    }

    // Shared by the back button, the back gesture, and the system back
    // event so all three behave identically: dismiss the input overlay if
    // it's open, otherwise navigate page history, otherwise exit the app.
    private fun handleBackAction() {
        when {
            inputOverlay != null -> dismissInputOverlay()
            zoomPanel.visibility == View.VISIBLE -> hideZoomPanel()
            canGoBack -> geckoSession.goBack()
            else -> finish()
        }
    }

    // Wear OS finishes the Activity on a left-edge swipe by default, which
    // is disabled at the theme level (see styles.xml). This detects that
    // same gesture manually and routes it through handleBackAction() so
    // swipe-back behaves exactly like the back button and browser-history
    // back, instead of ever blanking/closing the app out from under a page.
    // Never consumes the event, so normal scrolling/tapping on the page is
    // untouched.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = ev.rawX
                touchDownY = ev.rawY
                touchDownTime = System.currentTimeMillis()
            }
            MotionEvent.ACTION_UP -> {
                val dx = ev.rawX - touchDownX
                val dy = abs(ev.rawY - touchDownY)
                val elapsed = System.currentTimeMillis() - touchDownTime
                if (touchDownX <= edgeSwipeThresholdPx &&
                    dx >= edgeSwipeMinDistancePx &&
                    dy < dx * 0.6f &&
                    elapsed < 600
                ) {
                    handleBackAction()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun showInputOverlay() {
        if (inputOverlay != null) return

        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val editText = EditText(this).apply {
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            hint = "Type here..."
            textSize = 16f
            setBackgroundColor(Color.DKGRAY)
            setPadding(24, 24, 24, 24)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                leftMargin = 40
                rightMargin = 40
            }
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    submitTextToFocusedField(text.toString())
                    dismissInputOverlay()
                    true
                } else {
                    false
                }
            }
        }

        // Cancel button, so backing out of typing doesn't rely on the
        // swipe gesture or back button working around the keyboard.
        // Top-center rather than a corner, since corners get clipped by
        // the round bezel.
        val cancelButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            background = null
            contentDescription = "Cancel"
            layoutParams = FrameLayout.LayoutParams(
                (32 * resources.displayMetrics.density).toInt(),
                (32 * resources.displayMetrics.density).toInt(),
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                topMargin = (30 * resources.displayMetrics.density).toInt()
            }
            setOnClickListener { dismissInputOverlay() }
        }

        overlay.addView(editText)
        overlay.addView(cancelButton)
        rootLayout.addView(overlay)
        inputOverlay = overlay

        editText.requestFocus()
        editText.postDelayed({
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(editText, InputMethodManager.SHOW_FORCED)
        }, 100)
    }

    private fun dismissInputOverlay() {
        inputOverlay?.let {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(it.windowToken, 0)
            rootLayout.removeView(it)
            inputOverlay = null
        }
    }

    private fun submitTextToFocusedField(text: String) {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")

        geckoSession.loadUri(
            "javascript:(function(){" +
                "var el=document.activeElement;" +
                "if(!el)return;" +
                "var val=\"$escaped\";" +
                "if('value' in el){" +
                "var proto=Object.getPrototypeOf(el);" +
                "var setter=Object.getOwnPropertyDescriptor(proto,'value');" +
                "if(setter&&setter.set){setter.set.call(el,val);}else{el.value=val;}" +
                "}else if(el.isContentEditable){el.textContent=val;}" +
                "el.dispatchEvent(new Event('input',{bubbles:true}));" +
                "el.dispatchEvent(new Event('change',{bubbles:true}));" +
                "})();"
        )
    }

    override fun onPause() {
        geckoSession.setActive(false)
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        geckoSession.setActive(true)
    }

    override fun onDestroy() {
        geckoSession.close()
        super.onDestroy()
    }

    override fun onBackPressed() {
        handleBackAction()
    }
}

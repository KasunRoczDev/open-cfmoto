// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Alexandru <https://alexandru.rocks> and the OpenCfMoto contributors.
// Part of OpenCfMoto. Free software under the GNU AGPL v3 or later; see LICENSE and NOTICE.
package dev.zanderp.opencfmoto

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.zanderp.opencfmoto.aa.AaInput
import dev.zanderp.opencfmoto.ui.design.OcmTheme
import dev.zanderp.opencfmoto.ui.ride.DashViewScreen
import dev.zanderp.opencfmoto.ui.ride.DashViewUi

/**
 * Full-screen in-app dash: live AA video when connected, or Map / GPX phone preview when
 * [GpxSession] is active without a bike (debug / offline).
 */
class HudViewActivity : AppCompatActivity() {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (RemotePad.consume(this, event)) return true
        return super.dispatchKeyEvent(event)
    }

    private lateinit var surface: SurfaceView
    private lateinit var gpxHost: FrameLayout
    private var attached = false
    private var noSessionToastAt = 0L
    private var fullscreen = false
    private var gpxPreview = false
    private var padShown = true
    private var ui by mutableStateOf(DashViewUi("Dash view", topBar = true, pad = true, hint = true, gpxPreview = false))
    private var gpxUi: GpxDashUi? = null
    private var boundSessionKey: String? = null
    private lateinit var insetsController: WindowInsetsControllerCompat

    /** Recompute what the screen shows from the mode flags (replaces the old per-View visibility). */
    private fun render(title: String = ui.title, hint: Boolean = ui.hint) {
        ui = DashViewUi(
            title = title,
            topBar = !fullscreen && !gpxPreview,
            pad = !fullscreen && !gpxPreview && padShown,
            hint = hint && !gpxPreview,
            gpxPreview = gpxPreview,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surface = SurfaceView(this)
        gpxHost = FrameLayout(this).apply { visibility = View.GONE }
        insetsController = WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullscreen) setFullscreen(false) else finish()
            }
        })

        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                if (gpxPreview) return
                val pipe = AaVideoBridge.pipeline
                if (pipe == null) {
                    LogBus.log("[HUD] no AA pipeline — preview will show once Android Auto is live")
                    return
                }
                if (!attached) {
                    pipe.setPreviewSurface(holder.surface, width, height)
                    attached = true
                    LogBus.log("[HUD] preview surface attached ${width}x$height")
                } else {
                    pipe.updatePreviewSize(width, height)
                }
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (attached) {
                    AaVideoBridge.pipeline?.clearPreviewSurface()
                    attached = false
                    LogBus.log("[HUD] preview surface detached")
                }
            }
        })

        surface.setOnTouchListener { _, e -> onSurfaceTouch(e); true }

        setContent {
            OcmTheme {
                DashViewScreen(
                    ui = ui,
                    surface = surface,
                    gpxHost = gpxHost,
                    onClose = { finish() },
                    onMapHub = { GpxActivity.start(this) },
                    onTheme = ::cycleMapTheme,
                    onTogglePad = ::toggleControls,
                    onFullscreen = { setFullscreen(!fullscreen) },
                    onKey = ::key,
                    onScroll = ::scroll,
                    onVoice = {
                        AaKeys.ensureMic(this, REQ_MIC)
                        key(AaInput.KEY_ASSISTANT)
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Force rebuild when Hub opens a new GPX / nav session into an existing preview.
        refreshMode(forceReload = true)
    }

    override fun onResume() {
        super.onResume()
        refreshMode(forceReload = false)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // We keep configChanges so the map isn't torn down — still reflow the Waze chrome.
        gpxUi?.onConfigurationChanged()
    }

    override fun onPause() {
        if (isFinishing) stopGpxPreview()
        super.onPause()
    }

    override fun onDestroy() {
        stopGpxPreview()
        super.onDestroy()
    }

    private fun sessionKey(): String =
        "${GpxSession.mode}|${GpxSession.trackName}|${GpxSession.trackFile?.absolutePath}|${GpxSession.destination?.lat}|${GpxSession.destination?.lon}"

    private fun refreshMode(forceReload: Boolean) {
        // Live Android Auto video always wins: the phone Map preview is only a fallback for when
        // there's no bike pipeline. This keeps a previously-opened preview from sticking over the
        // real HUD once the bike connects.
        val live = AaVideoBridge.pipeline != null
        val wantGpx = !live && GpxSession.active
        if (wantGpx) {
            val key = sessionKey()
            val needReload = forceReload ||
                intent.getBooleanExtra(EXTRA_FORCE_RELOAD, false) ||
                key != boundSessionKey
            intent.removeExtra(EXTRA_FORCE_RELOAD)
            startGpxPreview(reload = needReload)
        } else {
            stopGpxPreview()
            render(title = "Dash view", hint = AaVideoBridge.pipeline == null)
        }
    }

    private fun startGpxPreview(reload: Boolean) {
        if (gpxPreview && gpxUi != null && !reload) {
            render()
            return
        }
        stopGpxPreview()
        gpxPreview = true
        gpxHost.removeAllViews()
        val root = try {
            LayoutInflater.from(this).inflate(R.layout.presentation_gpx, gpxHost, false)
        } catch (e: Exception) {
            gpxPreview = false
            LogBus.log("[HUD] inflate map chrome failed: $e")
            Toast.makeText(this, "Map UI failed to open", Toast.LENGTH_LONG).show()
            return
        }
        gpxHost.addView(
            root,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        val ui = GpxDashUi(this, root, { LogBus.log(it) }, isAlive = { !isFinishing && gpxPreview })
        val ok = try {
            ui.bind()
        } catch (e: Exception) {
            LogBus.log("[HUD] bind map failed: $e")
            false
        } catch (oom: OutOfMemoryError) {
            LogBus.log("[HUD] bind map OOM: $oom")
            false
        }
        if (!ok) {
            gpxPreview = false
            try { ui.release() } catch (_: Exception) {}
            gpxHost.removeAllViews()
            Toast.makeText(this, "Could not load map — try again from Map hub", Toast.LENGTH_LONG).show()
            return
        }
        gpxUi = ui
        boundSessionKey = sessionKey()
        // Map chrome has its own search / Hub / close — render() hides the Dash top bar and pad so
        // search isn't crushed under the punch-hole + app bar.
        // Waze-style: ride immersive — no system status bar eating the map.
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        render(
            title = when (GpxSession.mode) {
                GpxSession.Mode.GPX -> "GPX · ${GpxSession.trackName}"
                GpxSession.Mode.NAV_TO -> "Nav · ${GpxSession.trackName}"
                GpxSession.Mode.FREE_RIDE -> "Map preview"
            },
        )
        LogBus.log("[HUD] Map preview bound (${GpxSession.mode} '${GpxSession.trackName}')")
    }

    private fun stopGpxPreview() {
        if (!gpxPreview && gpxUi == null) {
            boundSessionKey = null
            return
        }
        gpxPreview = false
        boundSessionKey = null
        try { gpxUi?.release() } catch (_: Exception) {}
        gpxUi = null
        gpxHost.removeAllViews()
        insetsController.show(WindowInsetsCompat.Type.systemBars())
        render()
    }

    private fun cycleMapTheme() {
        val theme = NightPrefs.cycle(this)
        val on = NightPrefs.isNightNow(this)
        AaVideoBridge.nightSink?.invoke(on)
        gpxUi?.applyTheme()
        val extra = if (theme == MapTheme.AUTO) " (${if (on) "night" else "day"} now)" else ""
        Toast.makeText(this, getString(R.string.setup_toast_map_theme, getString(theme.labelRes)) + extra, Toast.LENGTH_SHORT).show()
    }

    private fun toggleControls() {
        if (gpxPreview) return
        padShown = !padShown
        render()
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        render()
        if (on) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            Toast.makeText(this, "Fullscreen — press Back to exit", Toast.LENGTH_SHORT).show()
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun onSurfaceTouch(e: MotionEvent) {
        if (gpxPreview) return
        val sink = AaVideoBridge.previewTouchSink
        if (sink == null) {
            val now = System.currentTimeMillis()
            if (now - noSessionToastAt > 3000) {
                noSessionToastAt = now
                Toast.makeText(this, "Connect to the bike first", Toast.LENGTH_SHORT).show()
            }
            return
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                forward(sink, e, e.actionIndex, AaInput.ACTION_DOWN)
            MotionEvent.ACTION_MOVE ->
                for (i in 0 until e.pointerCount) forward(sink, e, i, AaInput.ACTION_MOVE)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                forward(sink, e, e.actionIndex, AaInput.ACTION_UP)
            MotionEvent.ACTION_CANCEL ->
                for (i in 0 until e.pointerCount) forward(sink, e, i, AaInput.ACTION_UP)
        }
    }

    private fun forward(
        sink: (Int, Int, Int, Int) -> Unit,
        e: MotionEvent,
        index: Int,
        action: Int,
    ) {
        val src = mapToSource(e.getX(index), e.getY(index)) ?: return
        sink(action, e.getPointerId(index), src.first, src.second)
    }

    private fun mapToSource(vx: Float, vy: Float): Pair<Int, Int>? {
        val vw = surface.width
        val vh = surface.height
        val sw = BikeProfileHolder.aaUsableWidth
        val sh = BikeProfileHolder.aaUsableHeight
        if (vw == 0 || vh == 0 || sw == 0 || sh == 0) return null

        val srcAspect = sw.toFloat() / sh
        val viewAspect = vw.toFloat() / vh
        val rectW: Int
        val rectH: Int
        if (srcAspect < viewAspect) {
            rectH = vh; rectW = Math.round(vh * srcAspect)
        } else {
            rectW = vw; rectH = Math.round(vw / srcAspect)
        }
        val rectX = (vw - rectW) / 2
        val rectY = (vh - rectH) / 2

        val rx = vx - rectX
        val ry = vy - rectY
        if (rx < 0 || ry < 0 || rx >= rectW || ry >= rectH) return null
        val sx = (rx * sw / rectW).toInt().coerceIn(0, sw - 1)
        val sy = (ry * sh / rectH).toInt().coerceIn(0, sh - 1)
        return sx to sy
    }

    private fun key(code: Int) {
        if (gpxPreview) {
            Toast.makeText(this, "AA controls need a bike connection", Toast.LENGTH_SHORT).show()
            return
        }
        AaKeys.key(this, code, mapFirst = false)
    }

    private fun scroll(delta: Int) {
        if (gpxPreview) {
            Toast.makeText(this, "AA controls need a bike connection", Toast.LENGTH_SHORT).show()
            return
        }
        AaKeys.scroll(this, delta, mapFirst = false)
    }

    companion object {
        private const val REQ_MIC = 72
        const val EXTRA_GPX_PREVIEW = "gpx_preview"
        const val EXTRA_FORCE_RELOAD = "gpx_force_reload"

        fun startGpxPreview(ctx: android.content.Context) {
            ctx.startActivity(
                Intent(ctx, HudViewActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_GPX_PREVIEW, true)
                    putExtra(EXTRA_FORCE_RELOAD, true)
                },
            )
        }
    }
}

package eu.kanade.tachiyomi.ui.reader.viewer.webtoon

import android.view.Choreographer
import android.view.MotionEvent
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class WebtoonAutoScroller(
    private val scope: CoroutineScope,
    private val recycler: WebtoonRecyclerView,
    private val preferences: ReaderPreferences,
) {
    private val _isScrolling = MutableStateFlow(false)
    val isScrolling = _isScrolling.asStateFlow()

    private var lastFrameNanos = 0L
    private var accumulatedDy = 0f
    private var isPausedByTouch = false
    private var resumeJob: Job? = null

    private var speedPxPerSec = computeSpeedPxPerSec(preferences.webtoonAutoScrollSpeed.get())

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!_isScrolling.value || isPausedByTouch) return

            if (lastFrameNanos != 0L) {
                val dtSec = (frameTimeNanos - lastFrameNanos) / 1_000_000_000f
                val clampedDt = dtSec.coerceIn(0f, 0.1f)
                val totalDelta = speedPxPerSec * clampedDt + accumulatedDy
                val dy = totalDelta.toInt()

                if (dy > 0) {
                    accumulatedDy = totalDelta - dy
                    if (recycler.canScrollVertically(1)) {
                        recycler.scrollBy(0, dy)
                    } else {
                        stop()
                        return
                    }
                }
            }
            lastFrameNanos = frameTimeNanos
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        preferences.webtoonAutoScrollSpeed.changes()
            .onEach { speedLevel ->
                speedPxPerSec = computeSpeedPxPerSec(speedLevel)
            }
            .launchIn(scope)

        recycler.onUserTouchListener = { event ->
            handleTouchEvent(event)
        }
    }

    private fun handleTouchEvent(event: MotionEvent) {
        if (!_isScrolling.value) return

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resumeJob?.cancel()
                isPausedByTouch = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resumeJob?.cancel()
                resumeJob = scope.launch(Dispatchers.Main) {
                    delay(1200L)
                    if (_isScrolling.value && isPausedByTouch) {
                        isPausedByTouch = false
                        lastFrameNanos = 0L
                        accumulatedDy = 0f
                        Choreographer.getInstance().removeFrameCallback(frameCallback)
                        Choreographer.getInstance().postFrameCallback(frameCallback)
                    }
                }
            }
        }
    }

    fun start() {
        if (_isScrolling.value) return
        _isScrolling.value = true
        isPausedByTouch = false
        lastFrameNanos = 0L
        accumulatedDy = 0f
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    fun stop() {
        _isScrolling.value = false
        isPausedByTouch = false
        resumeJob?.cancel()
        lastFrameNanos = 0L
        accumulatedDy = 0f
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    fun pause() {
        stop()
    }

    fun toggle() {
        if (_isScrolling.value) stop() else start()
    }

    fun destroy() {
        stop()
        recycler.onUserTouchListener = null
    }

    companion object {
        fun computeSpeedPxPerSec(level: Int): Float {
            val clampedLevel = level.coerceIn(
                ReaderPreferences.WEBTOON_AUTO_SCROLL_SPEED_MIN,
                ReaderPreferences.WEBTOON_AUTO_SCROLL_SPEED_MAX,
            )
            return clampedLevel * 150f
        }
    }
}

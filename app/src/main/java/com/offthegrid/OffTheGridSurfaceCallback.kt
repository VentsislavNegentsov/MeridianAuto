package com.offthegrid

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer

class OffTheGridSurfaceCallback(private val carContext: CarContext) : SurfaceCallback {
    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        drawCanvas(surfaceContainer)
    }

    override fun onSurfaceSizeChanged(width: Int, height: Int) {
        // Automatically adapts to resolution/orientation changes
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {}
    override fun onStableAreaChanged(stableArea: Rect) {}

    private fun drawCanvas(container: SurfaceContainer) {
        val surface = container.surface ?: return
        val canvas: Canvas = try {
            surface.lockCanvas(null)
        } catch (e: Exception) {
            return
        }

        try {
            val isDark = carContext.isDarkMode
            canvas.drawColor(if (isDark) Color.parseColor("#121212") else Color.parseColor("#F4F4F4"))

            val paint = Paint().apply {
                color = if (isDark) Color.parseColor("#222222") else Color.parseColor("#E0E0E0")
                strokeWidth = 2f
            }
            val step = 100f
            var x = 0f
            while (x < container.width) {
                canvas.drawLine(x, 0f, x, container.height.toFloat(), paint)
                x += step
            }
            var y = 0f
            while (y < container.height) {
                canvas.drawLine(0f, y, container.width.toFloat(), y, paint)
                y += step
            }
        } finally {
            runCatching { surface.unlockCanvasAndPost(canvas) }
        }
    }
}

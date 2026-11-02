package com.offthegrid

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.graphics.Rect
import android.os.Bundle
import android.view.ViewGroup
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.LifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

class OffTheGridSurfaceCallback(
    private val carContext: CarContext,
    private val sessionLifecycleOwner: LifecycleOwner
) : SurfaceCallback {

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        runCatching { MapLibre.getInstance(carContext) }
        val displayManager = carContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

        virtualDisplay = displayManager.createVirtualDisplay(
            "OffTheGridMap",
            surfaceContainer.width,
            surfaceContainer.height,
            surfaceContainer.dpi,
            surfaceContainer.surface,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        )

        val display = virtualDisplay?.display ?: return
        presentation = Presentation(carContext, display).apply {
            val mv = MapView(carContext).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            mapView = mv
            mv.onCreate(Bundle())
            mv.getMapAsync { map ->
                map.setStyle(Style.Builder().fromJson(styleJson())) {
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(42.6977, 23.3219), 15.0))
                }
            }
            mv.onStart()
            mv.onResume()

            setContentView(mv)
            show()
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {}
    override fun onStableAreaChanged(stableArea: Rect) {}

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        mapView?.onPause()
        mapView?.onStop()
        mapView?.onDestroy()
        presentation?.dismiss()
        virtualDisplay?.release()
        presentation = null
        virtualDisplay = null
        mapView = null
    }
}

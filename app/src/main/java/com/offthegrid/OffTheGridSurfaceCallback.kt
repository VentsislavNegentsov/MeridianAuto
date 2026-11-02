package com.offthegrid

import android.app.Presentation
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.location.Location
import android.os.Bundle
import android.view.ViewGroup
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.LifecycleOwner
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

object CarNavState {
    var currentLocation: Location? = null
    var route: RouteData? = null
    var surfaceCallback: OffTheGridSurfaceCallback? = null
}

class OffTheGridSurfaceCallback(
    private val carContext: CarContext,
    private val sessionLifecycleOwner: LifecycleOwner
) : SurfaceCallback {

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private var mapView: MapView? = null

    init {
        CarNavState.surfaceCallback = this
    }

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
                map.setStyle(Style.Builder().fromJson(styleJson())) { style ->
                    style.addImage("car-arrow", createCarArrowBitmap())
                    style.addSource(org.maplibre.android.style.sources.GeoJsonSource("user-location"))
                    style.addLayer(
                        org.maplibre.android.style.layers.SymbolLayer("user-location-layer", "user-location").withProperties(
                            org.maplibre.android.style.layers.PropertyFactory.iconImage("car-arrow"),
                            org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap(true),
                            org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement(true),
                            org.maplibre.android.style.layers.PropertyFactory.iconSize(0.8f),
                            org.maplibre.android.style.layers.PropertyFactory.iconRotate(org.maplibre.android.style.expressions.Expression.get("bearing")),
                            org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment(org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_MAP)
                        )
                    )

                    style.addSource(org.maplibre.android.style.sources.GeoJsonSource("route"))
                    style.addLayer(
                        org.maplibre.android.style.layers.LineLayer("route-casing", "route").withProperties(
                            org.maplibre.android.style.layers.PropertyFactory.lineColor("#FFFFFF"),
                            org.maplibre.android.style.layers.PropertyFactory.lineWidth(12f),
                            org.maplibre.android.style.layers.PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
                            org.maplibre.android.style.layers.PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND)
                        )
                    )
                    style.addLayer(
                        org.maplibre.android.style.layers.LineLayer("route-line", "route").withProperties(
                            org.maplibre.android.style.layers.PropertyFactory.lineColor("#2D7FF9"),
                            org.maplibre.android.style.layers.PropertyFactory.lineWidth(8f),
                            org.maplibre.android.style.layers.PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
                            org.maplibre.android.style.layers.PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND)
                        )
                    )
                    updateRouteOnMap(style)
                    updateMapCamera(map)
                }
            }
            mv.onStart()
            mv.onResume()

            setContentView(mv)
            show()
        }
    }

    private fun createCarArrowBitmap(): Bitmap {
        val s = 96
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#2D7FF9")
            style = Paint.Style.FILL
        }
        val path = Path().apply {
            moveTo(s / 2f, 6f)
            lineTo(s - 12f, s - 12f)
            lineTo(s / 2f, s - 24f)
            lineTo(12f, s - 12f)
            close()
        }
        canvas.drawPath(path, paint)
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        canvas.drawPath(path, paint)
        return bmp
    }

    fun updateLocation(loc: Location) {
        mapView?.getMapAsync { map ->
            map.style?.let { style ->
                val point = org.maplibre.geojson.Point.fromLngLat(loc.longitude, loc.latitude)
                val feature = org.maplibre.geojson.Feature.fromGeometry(point).apply {
                    addNumberProperty("bearing", if (loc.hasBearing()) loc.bearing.toDouble() else 0.0)
                }
                style.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("user-location")?.setGeoJson(feature)
            }

            val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
            val zoom = when {
                speedKmh > 80f -> 14.5
                speedKmh > 40f -> 15.0
                else -> 16.0
            }
            val bearing = if (loc.hasBearing()) loc.bearing.toDouble() else map.cameraPosition.bearing

            map.easeCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(loc.latitude, loc.longitude))
                        .zoom(zoom)
                        .bearing(bearing)
                        .build()
                ),
                600
            )
        }
    }

    fun updateRoute(route: RouteData?) {
        mapView?.getMapAsync { map ->
            map.style?.let { style ->
                updateRouteOnMap(style)
            }
        }
    }

    private fun updateRouteOnMap(style: Style) {
        val r = CarNavState.route ?: return
        val coords = r.points.map { org.maplibre.geojson.Point.fromLngLat(it[0], it[1]) }
        val line = org.maplibre.geojson.LineString.fromLngLats(coords)
        val feature = org.maplibre.geojson.Feature.fromGeometry(line)
        style.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("route")?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(listOf(feature)))
    }

    private fun updateMapCamera(map: org.maplibre.android.maps.MapLibreMap) {
        val loc = CarNavState.currentLocation ?: Location("default").apply {
            latitude = 42.6977
            longitude = 23.3219
        }
        mapView?.getMapAsync { _ ->
            updateLocation(loc)
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {}
    override fun onStableAreaChanged(stableArea: Rect) {}

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        CarNavState.surfaceCallback = null
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

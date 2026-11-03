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
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.max
import kotlin.math.min

object CarNavState {
    var currentLocation: Location? = null
    var route: RouteData? = null
    var hazards: List<Hazard> = emptyList()
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
                    // Navigation arrow
                    style.addImage("car-arrow", createCarArrowBitmap())
                    style.addSource(GeoJsonSource("user-location"))
                    style.addLayer(
                        SymbolLayer("user-location-layer", "user-location").withProperties(
                            iconImage("car-arrow"),
                            iconAllowOverlap(true),
                            iconIgnorePlacement(true),
                            iconSize(0.8f),
                            iconRotate(Expression.get("bearing")),
                            iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP)
                        )
                    )

                    // Route lines
                    style.addSource(GeoJsonSource("route"))
                    style.addLayer(
                        org.maplibre.android.style.layers.LineLayer("route-casing", "route").withProperties(
                            lineColor("#FFFFFF"),
                            lineWidth(12f),
                            lineCap(Property.LINE_CAP_ROUND),
                            lineJoin(Property.LINE_JOIN_ROUND)
                        )
                    )
                    style.addLayer(
                        org.maplibre.android.style.layers.LineLayer("route-line", "route").withProperties(
                            lineColor("#2D7FF9"),
                            lineWidth(8f),
                            lineCap(Property.LINE_CAP_ROUND),
                            lineJoin(Property.LINE_JOIN_ROUND)
                        )
                    )

                    // Hazards icons
                    style.addImage("camera", badge("📷", 0xFFFF6D00.toInt()))
                    style.addImage("police", badge("🚓", 0xFF1565C0.toInt()))
                    style.addImage("hazard", badge("⚠️", 0xFFF9A825.toInt()))
                    style.addImage("crash", badge("💥", 0xFFD32F2F.toInt()))
                    style.addSource(GeoJsonSource("hazards"))
                    style.addLayer(
                        SymbolLayer("hazards-layer", "hazards").withProperties(
                            iconImage(Expression.get("type")),
                            iconAllowOverlap(true),
                            iconIgnorePlacement(true),
                            iconSize(0.6f)
                        )
                    )

                    updateRouteOnMap(style)
                    updateHazardsOnMap(style)
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

    fun updateLocation(rawLoc: Location) {
        val (loc, bearing) = getSnappedLocationAndBearing(rawLoc)

        mapView?.getMapAsync { map ->
            map.style?.let { style ->
                val point = org.maplibre.geojson.Point.fromLngLat(loc.longitude, loc.latitude)
                val feature = org.maplibre.geojson.Feature.fromGeometry(point).apply {
                    addNumberProperty("bearing", bearing.toDouble())
                }
                style.getSourceAs<GeoJsonSource>("user-location")?.setGeoJson(feature)
            }

            val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6f else 0f
            val zoom = when {
                speedKmh > 80f -> 14.5
                speedKmh > 40f -> 15.0
                else -> 16.0
            }

            map.easeCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(loc.latitude, loc.longitude))
                        .zoom(zoom)
                        .bearing(bearing.toDouble())
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

    fun updateHazards(list: List<Hazard>) {
        CarNavState.hazards = list
        mapView?.getMapAsync { map ->
            map.style?.let { style ->
                updateHazardsOnMap(style)
            }
        }
    }

    private fun getSnappedLocationAndBearing(loc: Location): Pair<Location, Float> {
        val r = CarNavState.route ?: return Pair(loc, if (loc.hasBearing()) loc.bearing else 0f)
        if (r.points.size < 2) return Pair(loc, if (loc.hasBearing()) loc.bearing else 0f)

        var minDist = Double.MAX_VALUE
        var bestLat = loc.latitude
        var bestLon = loc.longitude
        var bestBearing = if (loc.hasBearing()) loc.bearing else 0f

        val pts = r.points
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            val proj = projectToSegment(loc.latitude, loc.longitude, a[1], a[0], b[1], b[0])
            val dist = distM(loc.latitude, loc.longitude, proj.first, proj.second)
            if (dist < minDist) {
                minDist = dist
                bestLat = proj.first
                bestLon = proj.second
                bestBearing = computeBearing(a[1], a[0], b[1], b[0])
            }
        }

        if (minDist < 100.0) {
            val snapped = Location(loc).apply {
                latitude = bestLat
                longitude = bestLon
            }
            return Pair(snapped, bestBearing)
        }
        return Pair(loc, if (loc.hasBearing()) loc.bearing else 0f)
    }

    private fun projectToSegment(pLat: Double, pLon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Pair<Double, Double> {
        val dx = bLon - aLon
        val dy = bLat - aLat
        if (dx == 0.0 && dy == 0.0) return Pair(aLat, aLon)
        val t = max(0.0, min(1.0, ((pLon - aLon) * dx + (pLat - aLat) * dy) / (dx * dx + dy * dy)))
        return Pair(aLat + t * dy, aLon + t * dx)
    }

    private fun computeBearing(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Float {
        val dLon = Math.toRadians(bLon - aLon)
        val lat1 = Math.toRadians(aLat)
        val lat2 = Math.toRadians(bLat)
        val y = kotlin.math.sin(dLon) * kotlin.math.cos(lat2)
        val x = kotlin.math.cos(lat1) * kotlin.math.sin(lat2) - kotlin.math.sin(lat1) * kotlin.math.cos(lat2) * kotlin.math.cos(dLon)
        var brg = Math.toDegrees(kotlin.math.atan2(y, x)).toFloat()
        if (brg < 0) brg += 360f
        return brg
    }

    private fun updateRouteOnMap(style: Style) {
        val r = CarNavState.route ?: return
        val coords = r.points.map { org.maplibre.geojson.Point.fromLngLat(it[0], it[1]) }
        val line = org.maplibre.geojson.LineString.fromLngLats(coords)
        val feature = org.maplibre.geojson.Feature.fromGeometry(line)
        style.getSourceAs<GeoJsonSource>("route")?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(listOf(feature)))
    }

    private fun updateHazardsOnMap(style: Style) {
        val list = CarNavState.hazards
        val features = list.map { h ->
            org.maplibre.geojson.Feature.fromGeometry(org.maplibre.geojson.Point.fromLngLat(h.lon, h.lat)).apply {
                addStringProperty("type", h.type)
            }
        }
        style.getSourceAs<GeoJsonSource>("hazards")?.setGeoJson(org.maplibre.geojson.FeatureCollection.fromFeatures(features))
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

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        // Pad map view to keep center focused in unobstructed area without shrinking the VirtualDisplay
        val left = visibleArea.left
        val top = visibleArea.top
        mapView?.getMapAsync { map ->
            map.setPadding(left, top, 0, 0)
        }
    }

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

package com.offthegrid

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Paint
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.OnCameraTrackingChangedListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.*

/*
 * OffTheGrid - Waze-style navigation on a satellite map.
 * Dependencies / permissions: see the build files delivered with this project.
 * Traffic layer: paste a free TomTom key below (developer.tomtom.com). Empty = no traffic overlay.
 */
private const val TOMTOM_KEY = ""
private const val UA = "OffTheGrid/1.0 (your@email.com)"   // required by Nominatim/Overpass policy

private val WBlue = Color(0xFF2D7FF9)
private val WBlueDark = Color(0xFF1B5FCC)
private val WGreen = Color(0xFF1E9E4A)

// ------------------------------- DATA --------------------------------

data class Step(val type: String, val mod: String, val name: String, val dist: Double,
                val lat: Double, val lon: Double, val exit: Int = 0)
data class RouteData(val points: List<DoubleArray>, val steps: List<Step>, val dist: Double, val dur: Double)
data class Hazard(val type: String, val lat: Double, val lon: Double, val ts: Long = 0L)  // camera|police|hazard|crash
data class Place(val name: String, val lat: Double, val lon: Double)

// ------------------------------ HELPERS ------------------------------

fun distM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(bLat - aLat)
    val dLon = Math.toRadians(bLon - aLon)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}

fun fmtDist(m: Double): String = when {
    m < 100 -> "${(m / 10).roundToInt() * 10} m"
    m < 1000 -> "${(m / 50).roundToInt() * 50} m"
    else -> "%.1f km".format(m / 1000)
}

fun fmtDur(s: Double): String {
    val min = max(1, (s / 60).roundToInt())
    return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
}

fun arrivalTime(sec: Double): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(System.currentTimeMillis() + (sec * 1000).toLong()))

fun ordinal(n: Int) = when (n) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${n}th" }

fun isRoundabout(t: String) = t == "roundabout" || t == "rotary" || t == "roundabout turn"

fun instruction(s: Step): String {
    val road = if (s.name.isNotBlank()) " onto ${s.name}" else ""
    val side = if (s.mod.contains("left")) "left" else "right"
    return when {
        s.type == "arrive" -> "You have arrived"
        s.type == "depart" -> "Head out$road"
        isRoundabout(s.type) -> if (s.exit > 0) "Take the ${ordinal(s.exit)} exit$road" else "Enter the roundabout"
        s.type == "exit roundabout" || s.type == "exit rotary" -> "Exit the roundabout$road"
        s.type == "off ramp" -> "Take the exit on the $side$road"
        s.type == "on ramp" -> "Take the ramp$road"
        s.type == "fork" -> "Keep $side at the fork$road"
        s.type == "merge" -> "Merge$road"
        s.mod == "uturn" -> "Make a U-turn$road"
        s.mod.isBlank() || s.mod == "straight" -> "Continue$road"
        s.mod.contains("slight") -> "Keep ${s.mod.removePrefix("slight ")}$road"
        else -> "Turn ${s.mod}$road"
    }
}

suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
    val c = URL(url).openConnection() as HttpURLConnection
    c.setRequestProperty("User-Agent", UA); c.connectTimeout = 15000; c.readTimeout = 20000
    c.inputStream.bufferedReader().use { it.readText() }
}

suspend fun httpPost(url: String, body: String): String = withContext(Dispatchers.IO) {
    val c = URL(url).openConnection() as HttpURLConnection
    c.requestMethod = "POST"; c.doOutput = true
    c.setRequestProperty("User-Agent", UA); c.connectTimeout = 15000; c.readTimeout = 30000
    c.outputStream.use { it.write(body.toByteArray()) }
    c.inputStream.bufferedReader().use { it.readText() }
}

// -------------------------- NETWORK SERVICES -------------------------

suspend fun searchPlaces(q: String): List<Place> {
    val j = JSONArray(httpGet("https://nominatim.openstreetmap.org/search?format=json&limit=6&q=" + URLEncoder.encode(q, "UTF-8")))
    return List(j.length()) {
        val o = j.getJSONObject(it)
        Place(o.getString("display_name"), o.getDouble("lat"), o.getDouble("lon"))
    }
}

suspend fun fetchRoute(aLat: Double, aLon: Double, bLat: Double, bLon: Double): RouteData? {
    val url = "https://router.project-osrm.org/route/v1/driving/$aLon,$aLat;$bLon,$bLat" +
            "?overview=full&geometries=geojson&steps=true"
    val r = JSONObject(httpGet(url)).optJSONArray("routes")?.optJSONObject(0) ?: return null
    val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
    val pts = List(coords.length()) { val c = coords.getJSONArray(it); doubleArrayOf(c.getDouble(0), c.getDouble(1)) }
    val st = r.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
    val steps = List(st.length()) {
        val s = st.getJSONObject(it); val m = s.getJSONObject("maneuver"); val l = m.getJSONArray("location")
        Step(m.getString("type"), m.optString("modifier", ""), s.optString("name", ""),
            s.getDouble("distance"), l.getDouble(1), l.getDouble(0), m.optInt("exit", 0))
    }
    return RouteData(pts, steps, r.getDouble("distance"), r.getDouble("duration"))
}

suspend fun fetchCameras(s: Double, w: Double, n: Double, e: Double): List<Hazard> {
    val q = "[out:json][timeout:20];(node[\"highway\"=\"speed_camera\"]($s,$w,$n,$e););out;"
    val j = JSONObject(httpPost("https://overpass-api.de/api/interpreter", "data=" + URLEncoder.encode(q, "UTF-8")))
    val el = j.getJSONArray("elements")
    return List(el.length()) { val o = el.getJSONObject(it); Hazard("camera", o.getDouble("lat"), o.getDouble("lon")) }
}

// --------------------- LOCAL (USER) REPORTS STORE --------------------
// Waze reports are crowd-sourced through Waze's servers (no public API). Here they are kept
// on-device for 2 hours. To share between users you need a backend (e.g. Firebase Firestore).

private const val REPORT_TTL = 2 * 60 * 60 * 1000L

fun loadReports(ctx: Context): List<Hazard> {
    val raw = ctx.getSharedPreferences("offthegrid", Context.MODE_PRIVATE).getString("reports", "[]") ?: "[]"
    val a = JSONArray(raw); val now = System.currentTimeMillis()
    return List(a.length()) { a.getJSONObject(it) }
        .map { Hazard(it.getString("t"), it.getDouble("la"), it.getDouble("lo"), it.getLong("ts")) }
        .filter { now - it.ts < REPORT_TTL }
}

fun saveReports(ctx: Context, list: List<Hazard>) {
    val a = JSONArray()
    list.forEach { a.put(JSONObject().put("t", it.type).put("la", it.lat).put("lo", it.lon).put("ts", it.ts)) }
    ctx.getSharedPreferences("offthegrid", Context.MODE_PRIVATE).edit().putString("reports", a.toString()).apply()
}

// ----------------------------- MAP STYLE -----------------------------

fun styleJson(): String {
    val esri = "https://server.arcgisonline.com/ArcGIS/rest/services"
    val traffic = if (TOMTOM_KEY.isBlank()) "" else """,
      "traffic":{"type":"raster","tileSize":256,"maxzoom":18,
        "tiles":["https://api.tomtom.com/traffic/map/4/tile/flow/relative0/{z}/{x}/{y}.png?key=$TOMTOM_KEY"]}"""
    val trafficLayer = if (TOMTOM_KEY.isBlank()) "" else
        """,{"id":"traffic","type":"raster","source":"traffic","paint":{"raster-opacity":0.9}}"""
    return """
    {"version":8,
     "sources":{
      "sat":{"type":"raster","tileSize":256,"maxzoom":19,"attribution":"Imagery © Esri, Maxar, Earthstar Geographics",
        "tiles":["$esri/World_Imagery/MapServer/tile/{z}/{y}/{x}"]},
      "roads":{"type":"raster","tileSize":256,"maxzoom":19,
        "tiles":["$esri/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}"]},
      "places":{"type":"raster","tileSize":256,"maxzoom":19,
        "tiles":["$esri/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}"]}$traffic},
     "layers":[
      {"id":"sat","type":"raster","source":"sat"}$trafficLayer,
      {"id":"roads","type":"raster","source":"roads"},
      {"id":"places","type":"raster","source":"places"}]}
    """.trimIndent()
}

/** Round map badge (emoji on coloured circle with white ring), like Waze report pins. */
fun badge(emoji: String, bg: Int): Bitmap {
    val s = 96
    val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = android.graphics.Color.WHITE; c.drawCircle(s / 2f, s / 2f, s / 2f, p)
    p.color = bg; c.drawCircle(s / 2f, s / 2f, s / 2f - 6f, p)
    p.textSize = 50f; p.textAlign = Paint.Align.CENTER
    c.drawText(emoji, s / 2f, s / 2f - (p.descent() + p.ascent()) / 2f, p)
    return bmp
}

// ----------------------------- ACTIVITY ------------------------------

class MainActivity : ComponentActivity() {
    private var mapView: MapView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        setContent { MaterialTheme { NaviApp { mapView = it } } }
    }

    override fun onStart() { super.onStart(); mapView?.onStart() }
    override fun onResume() { super.onResume(); mapView?.onResume() }
    override fun onPause() { mapView?.onPause(); super.onPause() }
    override fun onStop() { mapView?.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView?.onLowMemory() }
    override fun onDestroy() { mapView?.onDestroy(); super.onDestroy() }
}

// ------------------------------- UI ----------------------------------

@SuppressLint("MissingPermission")
@Composable
fun NaviApp(onMapView: (MapView) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    var hasPerm by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    var myLoc by remember { mutableStateOf<Location?>(null) }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var dest by remember { mutableStateOf<Place?>(null) }
    var route by remember { mutableStateOf<RouteData?>(null) }
    var navigating by remember { mutableStateOf(false) }
    var stepIdx by remember { mutableIntStateOf(0) }
    var progressIdx by remember { mutableIntStateOf(0) }
    var remDist by remember { mutableDoubleStateOf(0.0) }
    var distToNext by remember { mutableDoubleStateOf(0.0) }
    var rerouting by remember { mutableStateOf(false) }
    var following by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf<String?>(null) }
    var cameras by remember { mutableStateOf<List<Hazard>>(emptyList()) }
    var reports by remember { mutableStateOf(loadReports(ctx)) }
    var reportMenu by remember { mutableStateOf(false) }
    var camsLoaded by remember { mutableStateOf(false) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPerm = it[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }
    LaunchedEffect(Unit) {
        if (!hasPerm) permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    DisposableEffect(hasPerm) {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val l = LocationListener { myLoc = it }
        if (hasPerm) {
            runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 3f, l) }
            runCatching { lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 10f, l) }
            myLoc = runCatching { lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) }.getOrNull()
        }
        onDispose { lm.removeUpdates(l) }
    }

    fun startRoute(p: Place) {
        val me = myLoc
        if (me == null) { msg = "Waiting for GPS fix..."; return }
        dest = p; results = emptyList(); msg = "Calculating route..."
        scope.launch {
            val r = runCatching { fetchRoute(me.latitude, me.longitude, p.lat, p.lon) }.getOrNull()
            if (r == null) { msg = "No route found"; return@launch }
            route = r; stepIdx = 0; progressIdx = 0; msg = null
            remDist = r.dist
            distToNext = r.steps.getOrNull(1)?.let { distM(me.latitude, me.longitude, it.lat, it.lon) } ?: 0.0
            val pts = r.points.map { LatLng(it[1], it[0]) }
            map?.easeCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.fromLatLngs(pts), 200), 800)
        }
    }

    fun cancelRoute() { navigating = false; route = null; dest = null; stepIdx = 0; progressIdx = 0; following = true }

    fun report(type: String) {
        val l = myLoc
        if (l != null) {
            reports = reports + Hazard(type, l.latitude, l.longitude, System.currentTimeMillis())
            saveReports(ctx, reports); msg = "Thanks for reporting!"
        } else msg = "No GPS fix yet"
        reportMenu = false
    }

    // ---- map layers ----
    LaunchedEffect(style) {
        val st = style ?: return@LaunchedEffect
        st.addSource(GeoJsonSource("route"))
        st.addLayer(LineLayer("route-casing", "route").withProperties(
            lineColor("#FFFFFF"), lineWidth(12f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
        st.addLayer(LineLayer("route-line", "route").withProperties(
            lineColor("#2D7FF9"), lineWidth(8f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
        st.addImage("camera", badge("📷", 0xFFFF6D00.toInt()))
        st.addImage("police", badge("🚓", 0xFF1565C0.toInt()))
        st.addImage("hazard", badge("⚠️", 0xFFF9A825.toInt()))
        st.addImage("crash", badge("💥", 0xFFD32F2F.toInt()))
        st.addSource(GeoJsonSource("hazards"))
        st.addLayer(SymbolLayer("hazards-layer", "hazards").withProperties(
            iconImage(Expression.get("type")), iconAllowOverlap(true), iconIgnorePlacement(true), iconSize(0.6f)))
        st.addSource(GeoJsonSource("dest"))
        st.addLayer(CircleLayer("dest-layer", "dest").withProperties(
            circleRadius(10f), circleColor("#D32F2F"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)))
    }

    // ---- location arrow + camera mode ----
    LaunchedEffect(hasPerm, style, route, navigating) {
        val st = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (!hasPerm) return@LaunchedEffect
        val lc = m.locationComponent
        if (!lc.isLocationComponentActivated) {
            lc.activateLocationComponent(LocationComponentActivationOptions.builder(ctx, st).useDefaultLocationEngine(true).build())
            lc.addOnCameraTrackingChangedListener(object : OnCameraTrackingChangedListener {
                override fun onCameraTrackingDismissed() { following = false }
                override fun onCameraTrackingChanged(currentMode: Int) { following = currentMode != CameraMode.NONE }
            })
        }
        lc.isLocationComponentEnabled = true
        lc.renderMode = RenderMode.GPS
        lc.cameraMode = when {
            navigating -> CameraMode.TRACKING_GPS
            route != null -> CameraMode.NONE
            else -> CameraMode.TRACKING
        }
        lc.zoomWhileTracking(if (navigating) 17.0 else 15.0)
        lc.tiltWhileTracking(if (navigating) 50.0 else 0.0)
    }

    // ---- push data to map (only the remaining part of the route while driving) ----
    LaunchedEffect(style, route, progressIdx, navigating) {
        val st = style ?: return@LaunchedEffect
        val pts = route?.points?.let { if (navigating) it.drop(progressIdx) else it }
        val fc = if (pts != null && pts.size > 1)
            FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(pts.map { Point.fromLngLat(it[0], it[1]) })))
        else FeatureCollection.fromFeatures(emptyList<Feature>())
        st.getSourceAs<GeoJsonSource>("route")?.setGeoJson(fc)
    }
    LaunchedEffect(style, dest) {
        val st = style ?: return@LaunchedEffect
        val fc = dest?.let { FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat))) }
            ?: FeatureCollection.fromFeatures(emptyList<Feature>())
        st.getSourceAs<GeoJsonSource>("dest")?.setGeoJson(fc)
    }
    LaunchedEffect(style, cameras, reports) {
        val st = style ?: return@LaunchedEffect
        val feats = (cameras + reports).map { h ->
            Feature.fromGeometry(Point.fromLngLat(h.lon, h.lat)).also { it.addStringProperty("type", h.type) }
        }
        st.getSourceAs<GeoJsonSource>("hazards")?.setGeoJson(FeatureCollection.fromFeatures(feats))
    }

    // ---- speed cameras from OSM ----
    LaunchedEffect(myLoc != null) {
        val me = myLoc ?: return@LaunchedEffect
        if (camsLoaded) return@LaunchedEffect
        camsLoaded = true
        cameras = runCatching { fetchCameras(me.latitude - .1, me.longitude - .15, me.latitude + .1, me.longitude + .15) }.getOrDefault(cameras)
    }
    LaunchedEffect(route) {
        val r = route ?: return@LaunchedEffect
        val la = r.points.map { it[1] }; val lo = r.points.map { it[0] }
        if (la.max() - la.min() > 2 || lo.max() - lo.min() > 3) return@LaunchedEffect
        val fresh = runCatching { fetchCameras(la.min() - .01, lo.min() - .01, la.max() + .01, lo.max() + .01) }.getOrNull() ?: return@LaunchedEffect
        cameras = (cameras + fresh).distinctBy { "${it.lat},${it.lon}" }
    }

    // ---- navigation progress / reroute ----
    LaunchedEffect(myLoc, navigating) {
        val l = myLoc ?: return@LaunchedEffect
        val r = route ?: return@LaunchedEffect
        if (!navigating) return@LaunchedEffect
        val next = r.steps.getOrNull(stepIdx + 1)
        if (next == null) { cancelRoute(); return@LaunchedEffect }
        distToNext = distM(l.latitude, l.longitude, next.lat, next.lon)
        if (distToNext < 25) {
            if (next.type == "arrive") { msg = "You have arrived"; cancelRoute(); return@LaunchedEffect }
            stepIdx++
        }
        remDist = distToNext + r.steps.drop(stepIdx + 1).sumOf { it.dist }
        var best = Double.MAX_VALUE; var bi = 0
        r.points.forEachIndexed { i, pt ->
            val d = distM(l.latitude, l.longitude, pt[1], pt[0])
            if (d < best) { best = d; bi = i }
        }
        progressIdx = bi
        if (best > 70 && !rerouting) {
            val d = dest ?: return@LaunchedEffect
            rerouting = true; msg = "Recalculating..."
            val nr = runCatching { fetchRoute(l.latitude, l.longitude, d.lat, d.lon) }.getOrNull()
            if (nr != null) { route = nr; stepIdx = 0; progressIdx = 0 }
            msg = null; rerouting = false
        }
    }

    LaunchedEffect(msg) {
        if (msg != null && msg != "Recalculating..." && msg != "Calculating route...") {
            kotlinx.coroutines.delay(2500); msg = null
        }
    }

    val warning = remember(myLoc, cameras, reports) {
        val l = myLoc ?: return@remember null
        (cameras + reports).map { it to distM(l.latitude, l.longitude, it.lat, it.lon) }
            .filter { it.second < 500 }.minByOrNull { it.second }
    }
    val kmh = ((myLoc?.speed ?: 0f) * 3.6f).roundToInt()
    val bottomPad = if (route != null) 118.dp else 24.dp

    // ================================ LAYOUT ================================
    Box(Modifier.fillMaxSize()) {

        AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
            MapView(c).also { mv ->
                mv.onCreate(null); mv.onStart(); mv.onResume()
                onMapView(mv)
                mv.getMapAsync { m ->
                    map = m
                    m.uiSettings.isCompassEnabled = false
                    m.uiSettings.isLogoEnabled = false
                    m.setStyle(Style.Builder().fromJson(styleJson())) { style = it }
                    m.cameraPosition = CameraPosition.Builder().target(LatLng(42.6977, 23.3219)).zoom(11.0).build()
                    m.addOnMapLongClickListener { ll -> startRoute(Place("Dropped pin", ll.latitude, ll.longitude)); true }
                }
            }
        })

        // ---------- TOP ----------
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(10.dp)) {
            val r = route
            if (navigating && r != null) {
                val s = r.steps.getOrNull(stepIdx + 1)
                if (s != null) {
                    Row(
                        Modifier.fillMaxWidth().shadow(8.dp, RoundedCornerShape(20.dp))
                            .background(WBlue, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ManeuverArrow(s.type, s.mod, s.exit, Color.White, 76.dp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(fmtDist(distToNext), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                            Text(instruction(s), color = Color.White, fontSize = 17.sp, maxLines = 2)
                        }
                    }
                    val t = r.steps.getOrNull(stepIdx + 2)
                    if (t != null && s.dist < 400) {
                        Row(
                            Modifier.padding(top = 6.dp).background(WBlueDark, RoundedCornerShape(16.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Then", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.width(8.dp))
                            ManeuverArrow(t.type, t.mod, t.exit, Color.White, 30.dp)
                        }
                    }
                }
            } else if (route == null) {
                Surface(shape = RoundedCornerShape(30.dp), shadowElevation = 8.dp) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("Where to?", fontSize = 17.sp) },
                        leadingIcon = { Text("🔍", fontSize = 18.sp) },
                        shape = RoundedCornerShape(30.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            scope.launch { results = runCatching { searchPlaces(query) }.getOrDefault(emptyList()) }
                        }),
                        trailingIcon = {
                            if (query.isNotEmpty()) TextButton(onClick = { query = ""; results = emptyList() }) { Text("✕") }
                        }
                    )
                }
                if (results.isNotEmpty()) {
                    Surface(Modifier.padding(top = 6.dp), shape = RoundedCornerShape(18.dp), shadowElevation = 8.dp) {
                        Column {
                            results.forEach { p ->
                                Row(Modifier.fillMaxWidth().clickable { startRoute(p) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("📍", fontSize = 18.sp); Spacer(Modifier.width(10.dp))
                                    Text(p.name, maxLines = 2, fontSize = 14.sp)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }

            warning?.let { (h, d) ->
                val (label, col) = when (h.type) {
                    "camera" -> "Speed camera" to Color(0xFFFF6D00)
                    "police" -> "Police" to Color(0xFF1565C0)
                    "crash" -> "Crash" to Color(0xFFD32F2F)
                    else -> "Hazard" to Color(0xFFF9A825)
                }
                Text("$label ahead · ${fmtDist(d)}", color = Color.White, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp).background(col, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
            }
            msg?.let {
                Text(it, color = Color.White, modifier = Modifier.padding(top = 8.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }

        // ---------- SPEED BUBBLE ----------
        if (hasPerm && myLoc != null) {
            Box(
                Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = bottomPad + 8.dp)
                    .size(66.dp).shadow(8.dp, CircleShape).background(Color.White, CircleShape)
                    .border(3.dp, Color(0xFFBDBDBD), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$kmh", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF263238))
                    Text("km/h", fontSize = 9.sp, color = Color.Gray)
                }
            }
        }

        // ---------- RIGHT BUTTONS ----------
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = bottomPad + 8.dp),
            horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (navigating && !following) {
                Row(Modifier.shadow(8.dp, RoundedCornerShape(26.dp)).background(WBlue, RoundedCornerShape(26.dp))
                    .clickable {
                        map?.locationComponent?.cameraMode = CameraMode.TRACKING_GPS; following = true
                    }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("➤ Re-center", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            RoundBtn(if (reportMenu) "✕" else "📢") { reportMenu = !reportMenu }
        }

        // ---------- REPORT SHEET ----------
        if (reportMenu) {
            Surface(Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPad + 84.dp, start = 12.dp, end = 12.dp).fillMaxWidth(),
                shape = RoundedCornerShape(24.dp), shadowElevation = 14.dp) {
                Column(Modifier.padding(16.dp)) {
                    Text("Report", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ReportTile("🚓", "Police") { report("police") }
                        ReportTile("⚠️", "Hazard") { report("hazard") }
                        ReportTile("📷", "Camera") { report("camera") }
                        ReportTile("💥", "Crash") { report("crash") }
                    }
                }
            }
        }

        // ---------- BOTTOM ----------
        route?.let { r ->
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), shadowElevation = 16.dp) {
                val dist = if (navigating) remDist else r.dist
                val dur = if (navigating && r.dist > 0) r.dur * remDist / r.dist else r.dur
                Row(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (navigating) {
                        RoundBtn("✕", bg = Color(0xFFEEEEEE), size = 48.dp) { cancelRoute() }
                        Spacer(Modifier.width(14.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(fmtDur(dur), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = WGreen)
                        Text("${fmtDist(dist)}  ·  ${arrivalTime(dur)}" + if (!navigating) "  ·  ${dest?.name?.take(24) ?: ""}" else "",
                            fontSize = 13.sp, color = Color.Gray, maxLines = 1)
                    }
                    if (!navigating) {
                        TextButton(onClick = { cancelRoute() }) { Text("Cancel") }
                        Spacer(Modifier.width(6.dp))
                        Button(onClick = { navigating = true; stepIdx = 0; progressIdx = 0 },
                            colors = ButtonDefaults.buttonColors(containerColor = WBlue),
                            shape = RoundedCornerShape(26.dp), modifier = Modifier.height(52.dp)) {
                            Text("Go", fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RoundBtn(label: String, bg: Color = Color.White, size: Dp = 56.dp, onClick: () -> Unit) {
    Box(Modifier.size(size).shadow(8.dp, CircleShape).clip(CircleShape).background(bg).clickable { onClick() },
        contentAlignment = Alignment.Center) { Text(label, fontSize = 22.sp) }
}

@Composable
fun ReportTile(emoji: String, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable { onClick() }.padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(58.dp).background(Color(0xFFF1F3F4), CircleShape), contentAlignment = Alignment.Center) {
            Text(emoji, fontSize = 28.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 12.sp)
    }
}

// ---------------- Standard turn arrows (drawn on Canvas) ----------------

fun turnAngle(mod: String): Double = Math.toRadians(when (mod) {
    "left" -> -90.0; "slight left" -> -45.0; "sharp left" -> -135.0
    "right" -> 90.0; "slight right" -> 45.0; "sharp right" -> 135.0
    else -> 0.0
})

@Composable
fun ManeuverArrow(type: String, mod: String, exit: Int = 0, color: Color = Color.White, size: Dp = 64.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        fun p(x: Float, y: Float) = Offset(x * w, y * w)
        val sw = w * 0.11f

        fun head(prev: Offset, e: Offset) {
            val dx = e.x - prev.x; val dy = e.y - prev.y; val len = hypot(dx, dy)
            if (len == 0f) return
            val ux = dx / len; val uy = dy / len
            for (deg in listOf(150.0, -150.0)) {
                val t = Math.toRadians(deg)
                val rx = (ux * cos(t) - uy * sin(t)).toFloat(); val ry = (ux * sin(t) + uy * cos(t)).toFloat()
                drawLine(color, e, Offset(e.x + rx * w * 0.24f, e.y + ry * w * 0.24f), strokeWidth = sw, cap = StrokeCap.Round)
            }
        }

        if (type == "arrive") {
            drawCircle(color, radius = w * 0.28f, center = p(.5f, .5f), style = Stroke(width = sw))
            drawCircle(color, radius = w * 0.10f, center = p(.5f, .5f))
            return@Canvas
        }

        if (isRoundabout(type)) {
            val c = p(.5f, .56f); val rad = w * 0.17f
            drawCircle(color, radius = rad, center = c, style = Stroke(width = sw * 0.8f))
            drawLine(color, p(.5f, .95f), Offset(c.x, c.y + rad), strokeWidth = sw, cap = StrokeCap.Round)
            val a = if (mod.isNotBlank()) turnAngle(mod) else when (exit) {
                1 -> Math.toRadians(90.0); 2 -> 0.0; 3 -> Math.toRadians(-90.0); else -> Math.toRadians(180.0)
            }
            val dirX = sin(a).toFloat(); val dirY = -cos(a).toFloat()
            val start = Offset(c.x + dirX * rad, c.y + dirY * rad)
            val end = Offset(c.x + dirX * (rad + w * 0.30f), c.y + dirY * (rad + w * 0.30f))
            drawLine(color, start, end, strokeWidth = sw, cap = StrokeCap.Round)
            head(start, end)
            return@Canvas
        }

        val pts: List<Offset> = if (mod == "uturn") {
            listOf(p(.68f, .88f), p(.68f, .35f), p(.5f, .16f), p(.32f, .35f), p(.32f, .62f))
        } else {
            val a = turnAngle(mod)
            if (a == 0.0) listOf(p(.5f, .88f), p(.5f, .12f))
            else {
                val c = p(.5f, .52f)
                listOf(p(.5f, .88f), c, Offset(c.x + (0.38 * w * sin(a)).toFloat(), c.y - (0.38 * w * cos(a)).toFloat()))
            }
        }
        for (i in 0 until pts.size - 1) drawLine(color, pts[i], pts[i + 1], strokeWidth = sw, cap = StrokeCap.Round)
        head(pts[pts.size - 2], pts.last())
    }
}
package com.offthegrid

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
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
 * ---------------------------------------------------------------
 *  SETUP
 * ---------------------------------------------------------------
 *  build.gradle (app) dependencies:
 *    implementation("org.maplibre.gl:android-sdk:11.5.2")
 *    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
 *    implementation("androidx.compose.material3:material3")
 *    implementation("androidx.compose.ui:ui")
 *    implementation("androidx.activity:activity-compose:1.9.2")
 *
 *  AndroidManifest.xml:
 *    <uses-permission android:name="android.permission.INTERNET"/>
 *    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
 *    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION"/>
 *
 *  Traffic layer: get a free key at developer.tomtom.com and paste below.
 *  Without a key the app still works, just without the traffic overlay.
 * ---------------------------------------------------------------
 */
private const val TOMTOM_KEY = ""          // <- your TomTom API key (traffic flow tiles)
private const val UA = "NaviDemo/1.0 (your@email.com)"   // required by Nominatim/Overpass policy

// ------------------------------- DATA --------------------------------

data class Step(val type: String, val mod: String, val name: String, val dist: Double, val lat: Double, val lon: Double)
data class RouteData(val points: List<DoubleArray>, val steps: List<Step>, val dist: Double, val dur: Double)
data class Hazard(val type: String, val lat: Double, val lon: Double, val ts: Long = 0L)   // camera | police | hazard
data class Place(val name: String, val lat: Double, val lon: Double)

// ------------------------------ HELPERS ------------------------------

fun distM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(bLat - aLat)
    val dLon = Math.toRadians(bLon - aLon)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}

fun fmtDist(m: Double): String =
    if (m < 1000) "${(m / 10).roundToInt() * 10} m" else "%.1f km".format(m / 1000)

fun fmtDur(s: Double): String {
    val min = (s / 60).roundToInt()
    return if (min < 60) "$min min" else "${min / 60} h ${min % 60} min"
}

fun instruction(s: Step): String {
    val road = if (s.name.isNotBlank()) " onto ${s.name}" else ""
    return when (s.type) {
        "arrive" -> "You have arrived"
        "depart" -> "Head out$road"
        "roundabout", "rotary" -> "Enter the roundabout"
        else -> when {
            s.mod == "uturn" -> "Make a U-turn$road"
            s.mod.isBlank() || s.mod == "straight" -> "Continue$road"
            s.mod.contains("slight") -> "Keep ${s.mod.removePrefix("slight ")}$road"
            else -> "Turn ${s.mod}$road"
        }
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

/** Place search via OpenStreetMap Nominatim. */
suspend fun searchPlaces(q: String): List<Place> {
    val j = JSONArray(httpGet("https://nominatim.openstreetmap.org/search?format=json&limit=6&q=" + URLEncoder.encode(q, "UTF-8")))
    return List(j.length()) {
        val o = j.getJSONObject(it)
        Place(o.getString("display_name"), o.getDouble("lat"), o.getDouble("lon"))
    }
}

/** Routing via OSRM demo server (OSM data). Swap for your own OSRM/Valhalla/GraphHopper in production. */
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
            s.getDouble("distance"), l.getDouble(1), l.getDouble(0))
    }
    return RouteData(pts, steps, r.getDouble("distance"), r.getDouble("duration"))
}

/** Speed cameras from OpenStreetMap (highway=speed_camera) through Overpass. */
suspend fun fetchCameras(s: Double, w: Double, n: Double, e: Double): List<Hazard> {
    val q = "[out:json][timeout:20];(node[\"highway\"=\"speed_camera\"]($s,$w,$n,$e););out;"
    val j = JSONObject(httpPost("https://overpass-api.de/api/interpreter", "data=" + URLEncoder.encode(q, "UTF-8")))
    val el = j.getJSONArray("elements")
    return List(el.length()) { val o = el.getJSONObject(it); Hazard("camera", o.getDouble("lat"), o.getDouble("lon")) }
}

// --------------------- LOCAL (USER) REPORTS STORE --------------------
// Police/hazard reports are crowd-sourced in Waze. There is no public API for that,
// so here they are stored on-device for 2 hours. To share them between users you need
// a small backend (e.g. Firebase Firestore) - replace load/save with calls to it.

private const val REPORT_TTL = 2 * 60 * 60 * 1000L

fun loadReports(ctx: Context): List<Hazard> {
    val raw = ctx.getSharedPreferences("navi", Context.MODE_PRIVATE).getString("reports", "[]") ?: "[]"
    val a = JSONArray(raw); val now = System.currentTimeMillis()
    return List(a.length()) { a.getJSONObject(it) }
        .map { Hazard(it.getString("t"), it.getDouble("la"), it.getDouble("lo"), it.getLong("ts")) }
        .filter { now - it.ts < REPORT_TTL }
}

fun saveReports(ctx: Context, list: List<Hazard>) {
    val a = JSONArray()
    list.forEach { a.put(JSONObject().put("t", it.type).put("la", it.lat).put("lo", it.lon).put("ts", it.ts)) }
    ctx.getSharedPreferences("navi", Context.MODE_PRIVATE).edit().putString("reports", a.toString()).apply()
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
    var remDist by remember { mutableDoubleStateOf(0.0) }
    var distToNext by remember { mutableDoubleStateOf(0.0) }
    var rerouting by remember { mutableStateOf(false) }
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

    // ---- own GPS listener (drives navigation logic) ----
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
            route = r; stepIdx = 0; msg = null
            remDist = r.dist; distToNext = r.steps.getOrNull(1)?.let { distM(me.latitude, me.longitude, it.lat, it.lon) } ?: 0.0
            val pts = r.points.map { LatLng(it[1], it[0]) }
            map?.easeCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.fromLatLngs(pts), 160), 800)
        }
    }

    fun cancelRoute() { navigating = false; route = null; dest = null; stepIdx = 0 }

    // ---- map layers setup ----
    LaunchedEffect(style) {
        val st = style ?: return@LaunchedEffect
        st.addSource(GeoJsonSource("route"))
        st.addLayer(LineLayer("route-casing", "route").withProperties(
            lineColor("#FFFFFF"), lineWidth(11f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
        st.addLayer(LineLayer("route-line", "route").withProperties(
            lineColor("#1A73E8"), lineWidth(7f), lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND)))
        st.addSource(GeoJsonSource("hazards"))
        st.addLayer(CircleLayer("hazards-layer", "hazards").withProperties(
            circleRadius(9f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(2.5f),
            circleColor(Expression.match(Expression.get("type"), Expression.color(0xFFFBC02D.toInt()),
                Expression.stop("camera", Expression.color(0xFFFF6D00.toInt())),
                Expression.stop("police", Expression.color(0xFF1565C0.toInt()))))))
        st.addSource(GeoJsonSource("dest"))
        st.addLayer(CircleLayer("dest-layer", "dest").withProperties(
            circleRadius(10f), circleColor("#D32F2F"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)))
    }

    // ---- location puck + camera mode ----
    LaunchedEffect(hasPerm, style, route, navigating) {
        val st = style ?: return@LaunchedEffect
        val m = map ?: return@LaunchedEffect
        if (!hasPerm) return@LaunchedEffect
        val lc = m.locationComponent
        if (!lc.isLocationComponentActivated) {
            lc.activateLocationComponent(LocationComponentActivationOptions.builder(ctx, st).useDefaultLocationEngine(true).build())
        }
        lc.isLocationComponentEnabled = true
        lc.renderMode = RenderMode.GPS                       // standard navigation arrow puck
        lc.cameraMode = when {
            navigating -> CameraMode.TRACKING_GPS            // follow + rotate with heading
            route != null -> CameraMode.NONE                 // route overview
            else -> CameraMode.TRACKING
        }
        lc.zoomWhileTracking(if (navigating) 17.0 else 15.0)
        lc.tiltWhileTracking(if (navigating) 50.0 else 0.0)
    }

    // ---- push data to map ----
    LaunchedEffect(style, route) {
        val st = style ?: return@LaunchedEffect
        val fc = route?.let { r ->
            FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(r.points.map { Point.fromLngLat(it[0], it[1]) })))
        } ?: FeatureCollection.fromFeatures(emptyList<Feature>())
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

    // ---- load speed cameras: around first fix, then around each route ----
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
        val off = r.points.minOf { distM(l.latitude, l.longitude, it[1], it[0]) }
        if (off > 70 && !rerouting) {
            val d = dest ?: return@LaunchedEffect
            rerouting = true; msg = "Rerouting..."
            val nr = runCatching { fetchRoute(l.latitude, l.longitude, d.lat, d.lon) }.getOrNull()
            if (nr != null) { route = nr; stepIdx = 0 }
            msg = null; rerouting = false
        }
    }

    // ---- nearest hazard warning (<500 m) ----
    val warning = remember(myLoc, cameras, reports) {
        val l = myLoc ?: return@remember null
        (cameras + reports).map { it to distM(l.latitude, l.longitude, it.lat, it.lon) }
            .filter { it.second < 500 }.minByOrNull { it.second }
    }

    // ================================ LAYOUT ================================
    Box(Modifier.fillMaxSize()) {

        AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
            MapView(c).also { mv ->
                mv.onCreate(null); mv.onStart(); mv.onResume()
                onMapView(mv)
                mv.getMapAsync { m ->
                    map = m
                    m.uiSettings.isCompassEnabled = true
                    m.setStyle(Style.Builder().fromJson(styleJson())) { style = it }
                    m.cameraPosition = CameraPosition.Builder().target(LatLng(42.6977, 23.3219)).zoom(11.0).build()
                    // long-press anywhere to set a destination
                    m.addOnMapLongClickListener { ll -> startRoute(Place("Dropped pin", ll.latitude, ll.longitude)); true }
                }
            }
        })

        // ---------- TOP: search or turn banner ----------
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding().padding(10.dp)) {
            val r = route
            if (navigating && r != null) {
                val s = r.steps.getOrNull(stepIdx + 1)
                if (s != null) {
                    Row(
                        Modifier.fillMaxWidth().background(Color(0xFF1B5E20), RoundedCornerShape(16.dp)).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ManeuverArrow(s.type, s.mod, Color.White, 64.dp)
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(fmtDist(distToNext), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                            Text(instruction(s), color = Color.White, fontSize = 16.sp)
                        }
                    }
                }
            } else {
                Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 6.dp) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("Where to?") },
                        shape = RoundedCornerShape(28.dp),
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
                    Surface(Modifier.padding(top = 6.dp), shape = RoundedCornerShape(16.dp), shadowElevation = 6.dp) {
                        Column {
                            results.forEach { p ->
                                Text(p.name, maxLines = 2, modifier = Modifier.fillMaxWidth()
                                    .clickable { startRoute(p) }.padding(14.dp), fontSize = 14.sp)
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }

            warning?.let { (h, d) ->
                val label = when (h.type) { "camera" -> "Speed camera"; "police" -> "Police reported"; else -> "Hazard" }
                val col = when (h.type) { "camera" -> Color(0xFFFF6D00); "police" -> Color(0xFF1565C0); else -> Color(0xFFF9A825) }
                Text("⚠ $label ahead · ${fmtDist(d)}", color = Color.White, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 8.dp).background(col, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
            }
            msg?.let {
                Text(it, color = Color.White, modifier = Modifier.padding(top = 8.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }

        // ---------- RIGHT: report button ----------
        Column(Modifier.align(Alignment.CenterEnd).padding(12.dp), horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (reportMenu) {
                listOf("police" to "🚓 Police", "hazard" to "⚠ Hazard", "camera" to "📷 Camera").forEach { (type, label) ->
                    ExtendedFloatingActionButton(onClick = {
                        val l = myLoc
                        if (l != null) {
                            reports = reports + Hazard(type, l.latitude, l.longitude, System.currentTimeMillis())
                            saveReports(ctx, reports); msg = "Reported — thanks!"
                        } else msg = "No GPS fix yet"
                        reportMenu = false
                    }) { Text(label) }
                }
            }
            FloatingActionButton(onClick = { reportMenu = !reportMenu }, containerColor = Color(0xFFFFC107)) {
                Text(if (reportMenu) "✕" else "!", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }

        // ---------- BOTTOM: route panel ----------
        route?.let { r ->
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), shadowElevation = 12.dp) {
                Row(Modifier.navigationBarsPadding().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val dist = if (navigating) remDist else r.dist
                        val dur = if (navigating && r.dist > 0) r.dur * remDist / r.dist else r.dur
                        Text(fmtDur(dur), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1B5E20))
                        Text("${fmtDist(dist)}  ·  ${dest?.name?.take(40) ?: ""}", fontSize = 13.sp, maxLines = 1)
                    }
                    if (!navigating) {
                        OutlinedButton(onClick = { cancelRoute() }) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { navigating = true; stepIdx = 0 }) { Text("Start") }
                    } else {
                        Button(onClick = { cancelRoute() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))) { Text("Exit") }
                    }
                }
            }
        }

        // ---------- LEGEND ----------
        Row(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = if (route != null) 110.dp else 14.dp)
            .background(Color(0xAA000000), RoundedCornerShape(10.dp)).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Dot(Color(0xFFFF6D00), "Camera"); Dot(Color(0xFF1565C0), "Police"); Dot(Color(0xFFFBC02D), "Hazard")
        }
    }
}

@Composable
fun Dot(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(c, CircleShape)); Spacer(Modifier.width(4.dp))
        Text(label, color = Color.White, fontSize = 11.sp)
    }
}

// ---------------- Standard turn arrows (drawn on Canvas) ----------------

fun turnAngle(mod: String): Double = Math.toRadians(when (mod) {
    "left" -> -90.0; "slight left" -> -45.0; "sharp left" -> -135.0
    "right" -> 90.0; "slight right" -> 45.0; "sharp right" -> 135.0
    else -> 0.0
})

@Composable
fun ManeuverArrow(type: String, mod: String, color: Color = Color.White, size: Dp = 64.dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        fun p(x: Float, y: Float) = Offset(x * w, y * w)
        val stroke = Stroke(width = w * 0.12f, cap = StrokeCap.Round)

        if (type == "arrive") {                       // destination marker
            drawCircle(color, radius = w * 0.28f, center = p(.5f, .5f), style = stroke)
            drawCircle(color, radius = w * 0.10f, center = p(.5f, .5f))
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
        for (i in 0 until pts.size - 1) drawLine(color, pts[i], pts[i + 1], strokeWidth = stroke.width, cap = StrokeCap.Round)

        // arrow head
        val e = pts.last(); val pr = pts[pts.size - 2]
        val dx = e.x - pr.x; val dy = e.y - pr.y; val len = hypot(dx, dy)
        val ux = dx / len; val uy = dy / len
        for (deg in listOf(150.0, -150.0)) {
            val t = Math.toRadians(deg)
            val rx = (ux * cos(t) - uy * sin(t)).toFloat(); val ry = (ux * sin(t) + uy * cos(t)).toFloat()
            drawLine(color, e, Offset(e.x + rx * w * 0.24f, e.y + ry * w * 0.24f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        }
    }
}

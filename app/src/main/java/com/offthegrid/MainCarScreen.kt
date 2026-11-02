package com.offthegrid

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Distance
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step as CarStep
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.TimeZone

class MainCarScreen(carContext: CarContext) : Screen(carContext) {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var myLoc: Location? = null
    private var dest: Place? = null
    private var routeData: RouteData? = null
    private var stepIdx: Int = 0
    private var remDist: Double = 0.0
    private var distToNext: Double = 0.0
    private var isCalculatingRoute = false

    private val locationListener = LocationListener { location ->
        myLoc = location
        updateNavigationState()
        invalidate()
    }

    init {
        startLocationUpdates()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val lm = carContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        runCatching {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 3f, locationListener)
        }
        runCatching {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 10f, locationListener)
        }
        myLoc = runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull()
    }

    fun startNavigation(place: Place) {
        val loc = myLoc ?: Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 42.6977
            longitude = 23.3219
        }

        isCalculatingRoute = true
        dest = place
        invalidate()

        scope.launch {
            val routes = runCatching {
                fetchRoutes(loc.latitude, loc.longitude, place.lat, place.lon)
            }.getOrDefault(emptyList())

            isCalculatingRoute = false
            if (routes.isEmpty()) {
                dest = null
                CarToast.makeText(carContext, "No route found", CarToast.LENGTH_SHORT).show()
            } else {
                routeData = routes[0]
                stepIdx = 0
                remDist = routes[0].dist
                val steps = routes[0].steps
                distToNext = if (steps.size > 1) {
                    distM(loc.latitude, loc.longitude, steps[1].lat, steps[1].lon)
                } else routes[0].dist

                carContext.getCarService(NavigationManager::class.java).navigationStarted()

                // Save to recents
                val recents = loadRecents(carContext)
                val updated = (listOf(place) + recents.filter { it.name != place.name }).take(5)
                saveRecents(carContext, updated)
            }
            invalidate()
        }
    }

    private fun cancelNavigation() {
        carContext.getCarService(NavigationManager::class.java).navigationEnded()
        dest = null
        routeData = null
        stepIdx = 0
        remDist = 0.0
        distToNext = 0.0
        invalidate()
    }

    private fun updateNavigationState() {
        val loc = myLoc ?: return
        val route = routeData ?: return
        val steps = route.steps

        if (stepIdx < steps.size) {
            val currentTarget = steps[stepIdx]
            distToNext = distM(loc.latitude, loc.longitude, currentTarget.lat, currentTarget.lon)

            if (distToNext < 25.0 && stepIdx < steps.size - 1) {
                stepIdx++
            }
        }

        dest?.let { d ->
            remDist = distM(loc.latitude, loc.longitude, d.lat, d.lon)
        }
    }

    override fun onGetTemplate(): Template {
        val currentDest = dest
        val currentRoute = routeData

        if (currentDest != null && currentRoute != null) {
            return buildNavigationTemplate(currentDest, currentRoute)
        }

        return buildMainListTemplate()
    }

    private fun buildNavigationTemplate(destination: Place, route: RouteData): Template {
        val steps = route.steps
        val currentStep = steps.getOrNull(stepIdx) ?: Step("arrive", "", destination.name, 0.0, destination.lat, destination.lon)
        val nextStep = steps.getOrNull(stepIdx + 1)

        val carStep = CarStep.Builder(instruction(currentStep))
            .setManeuver(Maneuver.Builder(getManeuverType(currentStep)).build())
            .build()

        val distance = Distance.create(distToNext, Distance.UNIT_METERS)

        val routingInfoBuilder = RoutingInfo.Builder()
            .setCurrentStep(carStep, distance)

        nextStep?.let { nStep ->
            val nextCarStep = CarStep.Builder(instruction(nStep))
                .setManeuver(Maneuver.Builder(getManeuverType(nStep)).build())
                .build()
            routingInfoBuilder.setNextStep(nextCarStep)
        }

        return NavigationTemplate.Builder()
            .setNavigationInfo(routingInfoBuilder.build())
            .setMapActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Report")
                            .setOnClickListener {
                                screenManager.push(ReportHazardCarScreen(carContext))
                            }
                            .build()
                    )
                    .addAction(
                        Action.Builder()
                            .setTitle("Cancel")
                            .setOnClickListener {
                                cancelNavigation()
                            }
                            .build()
                    )
                    .build()
            )
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Search")
                            .setOnClickListener {
                                screenManager.push(SearchCarScreen(carContext, ::startNavigation))
                            }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun buildMainListTemplate(): Template {
        if (isCalculatingRoute) {
            return ListTemplate.Builder()
                .setHeader(
                    Header.Builder()
                        .setTitle("Calculating Route...")
                        .setStartHeaderAction(Action.APP_ICON)
                        .build()
                )
                .setLoading(true)
                .build()
        }

        val listBuilder = ItemList.Builder()

        // Report Hazard row
        listBuilder.addItem(
            Row.Builder()
                .setTitle("⚠️ Report Road Hazard")
                .setOnClickListener {
                    screenManager.push(ReportHazardCarScreen(carContext))
                }
                .build()
        )

        // Search row
        listBuilder.addItem(
            Row.Builder()
                .setTitle("🔍 Search Destination")
                .setOnClickListener {
                    screenManager.push(SearchCarScreen(carContext, ::startNavigation))
                }
                .build()
        )

        val home = loadPlace(carContext, "home")
        val work = loadPlace(carContext, "work")
        val recents = loadRecents(carContext)

        home?.let { p ->
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("🏠 Home: ${p.name}")
                    .setOnClickListener { startNavigation(p) }
                    .build()
            )
        }

        work?.let { p ->
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("💼 Work: ${p.name}")
                    .setOnClickListener { startNavigation(p) }
                    .build()
            )
        }

        recents.forEach { p ->
            listBuilder.addItem(
                Row.Builder()
                    .setTitle("📍 ${p.name}")
                    .setOnClickListener { startNavigation(p) }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle("OffTheGrid")
                    .setStartHeaderAction(Action.APP_ICON)
                    .build()
            )
            .setSingleList(listBuilder.build())
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("Search")
                            .setOnClickListener {
                                screenManager.push(SearchCarScreen(carContext, ::startNavigation))
                            }
                            .build()
                    )
                    .build()
            )
            .build()
    }

    private fun getManeuverType(step: Step): Int {
        val mod = step.mod.lowercase()
        val type = step.type.lowercase()
        return when {
            type == "arrive" -> Maneuver.TYPE_DESTINATION
            type == "depart" -> Maneuver.TYPE_DEPART
            type.contains("roundabout") || type.contains("rotary") -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW
            mod.contains("uturn") -> Maneuver.TYPE_U_TURN_LEFT
            mod.contains("sharp left") -> Maneuver.TYPE_TURN_SHARP_LEFT
            mod.contains("sharp right") -> Maneuver.TYPE_TURN_SHARP_RIGHT
            mod.contains("slight left") -> Maneuver.TYPE_TURN_SLIGHT_LEFT
            mod.contains("slight right") -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
            mod.contains("left") -> Maneuver.TYPE_TURN_NORMAL_LEFT
            mod.contains("right") -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            else -> Maneuver.TYPE_STRAIGHT
        }
    }
}

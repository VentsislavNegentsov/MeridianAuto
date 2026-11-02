package com.offthegrid

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template

class ReportHazardCarScreen(carContext: CarContext) : Screen(carContext) {

    @SuppressLint("MissingPermission")
    override fun onGetTemplate(): Template {
        val listBuilder = ItemList.Builder()
        val hazards = listOf(
            "police" to "🚓 Police",
            "camera" to "📷 Speed Camera",
            "hazard" to "⚠️ Road Hazard",
            "crash" to "💥 Traffic Crash"
        )

        hazards.forEach { (type, label) ->
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(label)
                    .setOnClickListener {
                        val lm = carContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        val loc = runCatching {
                            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        }.getOrNull()

                        if (loc != null) {
                            val reports = loadReports(carContext)
                            val updated = reports + Hazard(type, loc.latitude, loc.longitude, System.currentTimeMillis())
                            saveReports(carContext, updated)
                            CarToast.makeText(carContext, "Reported $label", CarToast.LENGTH_SHORT).show()
                        } else {
                            CarToast.makeText(carContext, "GPS fix required to report hazard", CarToast.LENGTH_SHORT).show()
                        }
                        screenManager.pop()
                    }
                    .build()
            )
        }

        return ListTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle("Report Road Hazard")
                    .setStartHeaderAction(Action.BACK)
                    .build()
            )
            .setSingleList(listBuilder.build())
            .build()
    }
}

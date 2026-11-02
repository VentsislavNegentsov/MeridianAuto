package com.offthegrid

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class OffTheGridSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        return MainCarScreen(carContext)
    }
}

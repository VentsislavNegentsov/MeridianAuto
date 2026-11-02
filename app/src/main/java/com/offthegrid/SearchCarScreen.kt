package com.offthegrid

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SearchCarScreen(
    carContext: CarContext,
    private val onPlaceSelected: (Place) -> Unit
) : Screen(carContext) {

    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var searchResults: List<Place> = emptyList()
    private var isLoading = false

    private val searchCallback = object : SearchTemplate.SearchCallback {
        override fun onSearchSubmitted(searchText: String) {
            performSearch(searchText)
        }

        override fun onSearchTextChanged(searchText: String) {
            if (searchText.length >= 3) {
                performSearch(searchText)
            }
        }
    }

    override fun onGetTemplate(): Template {
        if (isLoading) {
            return SearchTemplate.Builder(searchCallback)
                .setHeaderAction(Action.BACK)
                .setLoading(true)
                .build()
        }

        val listBuilder = ItemList.Builder()
        for (place in searchResults) {
            listBuilder.addItem(
                Row.Builder()
                    .setTitle(place.name)
                    .setOnClickListener {
                        onPlaceSelected(place)
                        screenManager.pop()
                    }
                    .build()
            )
        }

        return SearchTemplate.Builder(searchCallback)
            .setHeaderAction(Action.BACK)
            .setLoading(false)
            .setItemList(listBuilder.build())
            .build()
    }

    private fun performSearch(query: String) {
        isLoading = true
        invalidate()
        scope.launch {
            searchResults = runCatching { searchPlaces(query) }.getOrDefault(emptyList())
            isLoading = false
            invalidate()
        }
    }
}

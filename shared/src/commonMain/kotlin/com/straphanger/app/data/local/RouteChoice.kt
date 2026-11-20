package com.straphanger.app.data.local

/** The line a rider says they took: [routeId], express or local. */
data class RouteChoice(val routeId: String, val isExpress: Boolean)

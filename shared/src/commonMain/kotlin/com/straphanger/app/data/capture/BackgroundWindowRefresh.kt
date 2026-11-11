package com.straphanger.app.data.capture

import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.SubwayNetworkData

/**
 * Moving the geofence window when no view model is running, e.g. from the platform receiver after
 * the app process was killed, or after a reboot. Only computes; the caller registers the result.
 */
object BackgroundWindowRefresh {

    /** The geofences to register around the given location: the nearest station complexes and the anchor. */
    fun windowAt(network: SubwayNetworkData, lat: Double, lng: Double): List<ActiveGeofenceSpec> =
        DynamicGeofenceWindowManager(SubwayGraphEngine(network)).computeRollingWindow(lat, lng).activeGeofences

    /**
     * True when [event] is a departure from the window anchor that no running coordinator will
     * handle. When [appIsHandlingEvents], the coordinator moves the window itself, so refreshing here
     * too would register twice.
     */
    fun shouldRefresh(event: GeofenceEvent, appIsHandlingEvents: Boolean): Boolean =
        event is GeofenceEvent.Exited &&
            event.geofenceId == DynamicGeofenceWindowManager.MACRO_ANCHOR_ID &&
            !appIsHandlingEvents
}

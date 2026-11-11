package com.straphanger.app.testutil

import com.straphanger.app.data.gtfs.NycSubwayNetworkCatalog
import com.straphanger.app.data.gtfs.SubwayGraphEngine
import com.straphanger.app.data.gtfs.SubwayNetworkData
import java.io.File

/** The generated network file, read from the source tree (host tests run with :shared as the working directory). */
object BundledNetwork {
    val file: File = File("src/commonMain/composeResources/${NycSubwayNetworkCatalog.BUNDLED_RESOURCE_PATH}")

    val data: SubwayNetworkData by lazy { NycSubwayNetworkCatalog.parse(file.readText()) }

    fun engine(): SubwayGraphEngine = SubwayGraphEngine(data)
}

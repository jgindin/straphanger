package com.straphanger.app.testutil

import com.straphanger.app.data.basemap.NycBasemap
import com.straphanger.app.data.basemap.NycBasemapCatalog
import java.io.File

/** The generated basemap file, read from the source tree (host tests run in :shared). */
object BundledBasemap {
  val file: File = File("src/commonMain/composeResources/${NycBasemapCatalog.BUNDLED_RESOURCE_PATH}")

  val data: NycBasemap by lazy { NycBasemapCatalog.parse(file.readText()) }
}

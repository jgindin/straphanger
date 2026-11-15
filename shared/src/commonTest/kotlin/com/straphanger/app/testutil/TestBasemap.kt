package com.straphanger.app.testutil

import com.straphanger.app.data.basemap.NycBasemap

/**
 * A basemap with nothing in it. View models in tests use it so that none tries to read the bundled
 * resource, which host tests cannot, and logs a warning while another test is watching the log.
 */
object TestBasemap {
  val empty = NycBasemap("2026-11-15", emptyList(), emptyList(), emptyList(), emptyList())
}

package com.straphanger.app

import com.straphanger.app.data.local.CompleteRideRecord
import com.straphanger.app.ui.rides.LegChoiceSelections

/** What the app does for the Rides tab beyond the view model: Undo, and the Log ride button. */
internal class RidesTabHooks(
  /** Deletes the ride and offers to undo it in the app's snackbar; false when it was not saved. */
  val onRideDeleted: (CompleteRideRecord) -> Boolean,
  /** Whether the list is scrolled to the top, where the Log ride button shows its text. */
  val onListAtTopChange: (Boolean) -> Unit,
  /** The trains chosen so far on rides with a change, kept while other tabs are shown. */
  val legSelections: LegChoiceSelections,
)

package com.straphanger.app.ui.status

import com.straphanger.app.data.commute.Commute
import com.straphanger.app.data.commute.CommuteStore
import com.straphanger.app.data.commute.InMemoryCommuteStore

/** What the platform currently lets the commute alerts do. */
data class CommuteAccess(
  val notificationsAllowed: Boolean = true,
  val exactAlarmsAllowed: Boolean = true,
)

/** What the Status tab's commutes need from the platform; see the public `App`. */
internal class CommuteControls(
  val store: CommuteStore = InMemoryCommuteStore(),
  val onCommutesChanged: (List<Commute>) -> Unit = {},
  val access: CommuteAccess = CommuteAccess(),
  val onFirstCommuteCreated: () -> Unit = {},
  val onOpenExactAlarmSettings: () -> Unit = {},
  val onOpenNotificationSettings: () -> Unit = {},
)

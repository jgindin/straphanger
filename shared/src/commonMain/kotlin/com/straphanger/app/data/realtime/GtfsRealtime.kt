@file:Suppress("MagicNumber") // protobuf field numbers from the GTFS-Realtime spec

package com.straphanger.app.data.realtime

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

/*
 * The few GTFS-Realtime fields the app reads (https://gtfs.org/realtime/reference/), written by
 * hand so no generated code or protobuf runtime is needed. Fields the app does not read are
 * skipped when decoding.
 */

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class FeedMessage(
  @ProtoNumber(1) val header: FeedHeader = FeedHeader(),
  @ProtoNumber(2) val entity: List<FeedEntity> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class FeedHeader(@ProtoNumber(3) val timestamp: Long = 0)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class FeedEntity(
  @ProtoNumber(1) val id: String = "",
  @ProtoNumber(3) val tripUpdate: TripUpdate? = null,
  @ProtoNumber(5) val alert: Alert? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class TripUpdate(
  @ProtoNumber(1) val trip: TripDescriptor = TripDescriptor(),
  @ProtoNumber(2) val stopTimeUpdate: List<StopTimeUpdate> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class TripDescriptor(
  @ProtoNumber(1) val tripId: String = "",
  @ProtoNumber(5) val routeId: String = "",
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class StopTimeUpdate(
  @ProtoNumber(2) val arrival: StopTimeEvent? = null,
  @ProtoNumber(3) val departure: StopTimeEvent? = null,
  @ProtoNumber(4) val stopId: String = "",
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class StopTimeEvent(@ProtoNumber(2) val time: Long = 0)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class Alert(
  @ProtoNumber(1) val activePeriod: List<TimeRange> = emptyList(),
  @ProtoNumber(5) val informedEntity: List<EntitySelector> = emptyList(),
  @ProtoNumber(7) val effect: Int = 0,
  @ProtoNumber(10) val headerText: TranslatedString? = null,
  @ProtoNumber(11) val descriptionText: TranslatedString? = null,
  /** The MTA's own classification, an extension on the standard alert. */
  @ProtoNumber(1001) val mercury: MercuryAlert? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class TimeRange(
  @ProtoNumber(1) val start: Long = 0,
  /** 0 when the alert has no end. */
  @ProtoNumber(2) val end: Long = 0,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class EntitySelector(@ProtoNumber(2) val routeId: String = "")

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class TranslatedString(@ProtoNumber(1) val translation: List<Translation> = emptyList()) {
  /** The English text, or the first translation, or empty. */
  fun text(): String =
    (translation.firstOrNull { it.language == "en" } ?: translation.firstOrNull())?.text.orEmpty()
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class Translation(
  @ProtoNumber(1) val text: String = "",
  @ProtoNumber(2) val language: String = "",
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
internal class MercuryAlert(@ProtoNumber(3) val alertType: String = "")

@OptIn(ExperimentalSerializationApi::class)
internal fun decodeFeed(bytes: ByteArray): FeedMessage = ProtoBuf.decodeFromByteArray(bytes)

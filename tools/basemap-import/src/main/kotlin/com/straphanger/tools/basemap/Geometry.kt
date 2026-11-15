package com.straphanger.tools.basemap

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Coordinates are stored as whole units of 1e-5 degree (about 1.1 m of latitude). */
const val UNITS_PER_DEGREE = 100_000

private const val METRES_PER_DEGREE_LAT = 110_950.0
private const val REFERENCE_LAT_DEGREES = 40.7
private const val DEGREES_PER_HALF_TURN = 180.0

/** Metres per unit of latitude and of longitude at New York's latitude. */
private const val METRES_PER_LAT_UNIT = METRES_PER_DEGREE_LAT / UNITS_PER_DEGREE
private val METRES_PER_LNG_UNIT =
  METRES_PER_LAT_UNIT * cos(REFERENCE_LAT_DEGREES * PI / DEGREES_PER_HALF_TURN)

/** A point in quantised units: [lng] and [lat] times [UNITS_PER_DEGREE], rounded. */
data class QPoint(val lng: Int, val lat: Int) {
  val xMetres: Double
    get() = lng * METRES_PER_LNG_UNIT

  val yMetres: Double
    get() = lat * METRES_PER_LAT_UNIT
}

/** Rounds a GeoJSON position to the nearest unit. */
fun quantise(lng: Double, lat: Double): QPoint =
  QPoint((lng * UNITS_PER_DEGREE).roundToInt(), (lat * UNITS_PER_DEGREE).roundToInt())

/** Drops points equal to the one before them. */
fun List<QPoint>.withoutRepeats(): List<QPoint> = filterIndexed { i, p ->
  i == 0 || p != this[i - 1]
}

/** Distance in metres from [p] to the segment [a]-[b]. */
fun distanceToSegmentMetres(p: QPoint, a: QPoint, b: QPoint): Double {
  val ax = a.xMetres
  val ay = a.yMetres
  val dx = b.xMetres - ax
  val dy = b.yMetres - ay
  val lengthSquared = dx * dx + dy * dy
  val t =
    if (lengthSquared == 0.0) 0.0
    else (((p.xMetres - ax) * dx + (p.yMetres - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
  return hypot(p.xMetres - (ax + t * dx), p.yMetres - (ay + t * dy))
}

/**
 * Douglas-Peucker: the fewest points of [line] such that no dropped point lies more than
 * [toleranceMetres] from the simplified line. The first and last points are kept. Iterative, so a
 * long shoreline cannot overflow the stack.
 */
fun simplifyLine(line: List<QPoint>, toleranceMetres: Double): List<QPoint> {
  if (line.size <= 2) return line
  val keep = BooleanArray(line.size)
  keep[0] = true
  keep[line.lastIndex] = true
  val stack = ArrayDeque<Pair<Int, Int>>()
  stack.addLast(0 to line.lastIndex)
  while (stack.isNotEmpty()) {
    val (first, last) = stack.removeLast()
    var farthest = -1
    var farthestDistance = toleranceMetres
    for (i in first + 1 until last) {
      val d = distanceToSegmentMetres(line[i], line[first], line[last])
      if (d > farthestDistance) {
        farthest = i
        farthestDistance = d
      }
    }
    if (farthest >= 0) {
      keep[farthest] = true
      stack.addLast(first to farthest)
      stack.addLast(farthest to last)
    }
  }
  return line.filterIndexed { i, _ -> keep[i] }
}

/**
 * Simplifies a closed ring (first point equal to last, or not). The ring is split at the point
 * farthest from its first point so that both halves have distinct ends. Returns the ring closed, or
 * null when fewer than three distinct points remain.
 */
fun simplifyRing(ring: List<QPoint>, toleranceMetres: Double): List<QPoint>? {
  val open =
    ring.withoutRepeats().let { if (it.size > 1 && it.first() == it.last()) it.dropLast(1) else it }
  if (open.size < MIN_RING_POINTS) return null
  val start = open.first()
  val split =
    open.indices.maxBy { i ->
      hypot(open[i].xMetres - start.xMetres, open[i].yMetres - start.yMetres)
    }
  val firstHalf = simplifyLine(open.subList(0, split + 1), toleranceMetres)
  val secondHalf = simplifyLine(open.subList(split, open.size) + start, toleranceMetres)
  val closed = firstHalf + secondHalf.drop(1)
  return closed.takeIf { it.size - 1 >= MIN_RING_POINTS }
}

private const val MIN_RING_POINTS = 3

/** Signed area in square metres; positive when the ring runs anticlockwise (north up). */
fun signedAreaSquareMetres(ring: List<QPoint>): Double {
  var twiceArea = 0.0
  for (i in ring.indices) {
    val a = ring[i]
    val b = ring[(i + 1) % ring.size]
    twiceArea += a.xMetres * b.yMetres - b.xMetres * a.yMetres
  }
  return twiceArea / 2
}

/** [ring] running anticlockwise when [anticlockwise], otherwise clockwise. */
fun List<QPoint>.oriented(anticlockwise: Boolean): List<QPoint> =
  if ((signedAreaSquareMetres(this) > 0) == anticlockwise) this else reversed()

/**
 * Encodes points as `[lng0, lat0, dLng1, dLat1, ...]`: the first point absolute, then each point as
 * the difference from the one before. Small differences keep the JSON short.
 */
fun deltaEncode(points: List<QPoint>): IntArray {
  val out = IntArray(points.size * 2)
  var previous = QPoint(0, 0)
  points.forEachIndexed { i, p ->
    out[2 * i] = p.lng - previous.lng
    out[2 * i + 1] = p.lat - previous.lat
    previous = p
  }
  return out
}

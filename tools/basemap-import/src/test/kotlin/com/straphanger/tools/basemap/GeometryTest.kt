package com.straphanger.tools.basemap

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeometryTest {

  @Test
  fun quantiseRoundsToTheNearestUnit() {
    assertEquals(QPoint(-7398000, 4075000), quantise(-73.979999, 40.75))
    assertEquals(QPoint(-7398001, 4075001), quantise(-73.980006, 40.750006))
  }

  @Test
  fun simplifyLineDropsPointsWithinToleranceAndKeepsOthers() {
    // About 1.1 m off the straight line: dropped at 2 m, kept at 0.5 m.
    val line = listOf(QPoint(0, 0), QPoint(500, 1), QPoint(1000, 0))
    assertEquals(listOf(QPoint(0, 0), QPoint(1000, 0)), simplifyLine(line, 2.0))
    assertEquals(line, simplifyLine(line, 0.5))
    // About 55 m off: always kept.
    val bend = listOf(QPoint(0, 0), QPoint(500, 50), QPoint(1000, 0))
    assertEquals(bend, simplifyLine(bend, 2.0))
    assertEquals(
      listOf(QPoint(0, 0), QPoint(1, 1)),
      simplifyLine(listOf(QPoint(0, 0), QPoint(1, 1)), 2.0),
    )
  }

  @Test
  fun simplifiedPointsStayWithinToleranceOfTheResult() {
    val wiggly = (0..400).map { i -> QPoint(i * 10, ((i * 7919) % 13) - 6) }
    val simplified = simplifyLine(wiggly, 2.0)
    assertTrue(simplified.size < wiggly.size)
    wiggly.forEach { p ->
      val nearest = simplified.zipWithNext().minOf { (a, b) -> distanceToSegmentMetres(p, a, b) }
      assertTrue(nearest <= 2.0 + 1e-9, "$p is $nearest m from the simplified line")
    }
  }

  @Test
  fun simplifyRingClosesTheRingAndDropsDegenerateOnes() {
    val square =
      listOf(QPoint(0, 0), QPoint(500, 0), QPoint(1000, 0), QPoint(1000, 1000), QPoint(0, 1000))
    val ring = simplifyRing(square, 2.0)!!
    assertEquals(
      listOf(QPoint(0, 0), QPoint(1000, 0), QPoint(1000, 1000), QPoint(0, 1000), QPoint(0, 0)),
      ring,
    )
    assertNull(simplifyRing(listOf(QPoint(0, 0), QPoint(1, 1), QPoint(0, 0)), 2.0))
    assertNull(
      simplifyRing(listOf(QPoint(0, 0), QPoint(500, 0), QPoint(1000, 0), QPoint(0, 0)), 2.0)
    )
  }

  @Test
  fun orientationAndArea() {
    val anticlockwise = listOf(QPoint(0, 0), QPoint(100, 0), QPoint(100, 100), QPoint(0, 100))
    assertTrue(signedAreaSquareMetres(anticlockwise) > 0)
    assertEquals(anticlockwise, anticlockwise.oriented(anticlockwise = true))
    assertEquals(anticlockwise.reversed(), anticlockwise.oriented(anticlockwise = false))
    // 100 units of longitude by 100 of latitude is about 84 m x 111 m.
    assertEquals(9_360.0, signedAreaSquareMetres(anticlockwise), 50.0)
  }

  @Test
  fun deltaEncodingStoresTheFirstPointThenDifferences() {
    assertContentEquals(
      intArrayOf(-7400000, 4070000, 5, -3, -2, 0),
      deltaEncode(
        listOf(QPoint(-7400000, 4070000), QPoint(-7399995, 4069997), QPoint(-7399997, 4069997))
      ),
    )
  }

  @Test
  fun chainLinesJoinsPiecesButNotAtJunctions() {
    val a = listOf(QPoint(0, 0), QPoint(10, 0))
    val b = listOf(QPoint(20, 0), QPoint(10, 0)) // reversed relative to a
    val c = listOf(QPoint(20, 0), QPoint(30, 0))
    assertEquals(
      listOf(listOf(QPoint(30, 0), QPoint(20, 0), QPoint(10, 0), QPoint(0, 0))),
      chainLines(listOf(b, a, c)),
    )
    // A third piece at (10, 0) makes it a junction: nothing is joined there.
    val spur = listOf(QPoint(10, 0), QPoint(10, 10))
    val chained = chainLines(listOf(a, b, spur))
    assertEquals(3, chained.size)
    // A single point is not a line.
    assertEquals(emptyList(), chainLines(listOf(listOf(QPoint(0, 0)))))
  }
}

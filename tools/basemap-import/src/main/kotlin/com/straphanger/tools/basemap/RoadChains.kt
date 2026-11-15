package com.straphanger.tools.basemap

/**
 * Joins street centerline pieces into longer polylines. The Street Centerline splits a road at
 * every intersection; a piece is joined to the next only where exactly two pieces meet at a point,
 * so junctions stay where they are. Longer lines simplify better and cost fewer JSON brackets.
 * Lines are visited in the order given, so the result is deterministic.
 */
fun chainLines(lines: List<List<QPoint>>): List<List<QPoint>> = LineChainer(lines).chains()

private class LineChainer(lines: List<List<QPoint>>) {
  private val pieces = lines.filter { it.size >= 2 }
  private val endsAt = HashMap<QPoint, MutableList<Int>>()
  private val used = BooleanArray(pieces.size)

  init {
    pieces.forEachIndexed { i, line ->
      endsAt.getOrPut(line.first()) { mutableListOf() }.add(i)
      endsAt.getOrPut(line.last()) { mutableListOf() }.add(i)
    }
  }

  fun chains(): List<List<QPoint>> =
    pieces.indices.mapNotNull { start ->
      if (used[start]) return@mapNotNull null
      used[start] = true
      val forward = extend(pieces[start])
      extend(forward.asReversed()).asReversed()
    }

  /** [chain] followed by every piece that continues it from its last point. */
  private fun extend(chain: List<QPoint>): List<QPoint> {
    val out = chain.toMutableList()
    var next = nextAt(out.last())
    while (next != null) {
      used[next] = true
      val piece = pieces[next]
      out += (if (piece.first() == out.last()) piece else piece.asReversed()).drop(1)
      next = nextAt(out.last())
    }
    return out
  }

  /** The unused piece that meets the chain at [node], when exactly two pieces end there. */
  private fun nextAt(node: QPoint): Int? =
    endsAt[node]?.takeIf { it.size == 2 }?.firstOrNull { !used[it] }
}

package com.straphanger.app.ui

/** Returns "1 ride" or "2 rides": the count followed by the singular or plural noun. */
fun countOf(count: Int, singular: String, plural: String = singular + "s"): String =
    "$count ${if (count == 1) singular else plural}"

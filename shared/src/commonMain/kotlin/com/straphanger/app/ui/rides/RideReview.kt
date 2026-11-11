package com.straphanger.app.ui.rides

import com.straphanger.app.data.local.RideEntity

/** What the needs-review banner offers for one ride. */
sealed interface ReviewAction {
    /** Re-resolve the ride on [routeId], express or local; [index] is the candidate's position. */
    data class ConfirmCandidate(val index: Int, val routeId: String, val isExpress: Boolean, val label: String) : ReviewAction

    /** Clear the review flag and keep the ride as recorded. */
    data object KeepAsIs : ReviewAction
}

data class RideReview(val prompt: String, val actions: List<ReviewAction>)

/**
 * The question and choices for a ride that needs review. A ride with route candidates (express or
 * local on the same stretch) offers one button per candidate. A ride without candidates was flagged
 * because a station name matched more than one station; the app cannot offer the alternatives, so
 * it offers only "Keep as is".
 */
fun reviewFor(ride: RideEntity): RideReview {
    val candidates = ride.ambiguousCandidates
    if (candidates.isEmpty()) {
        return RideReview(
            prompt = "A station name in this ride matches more than one station. " +
                "Check the stations; delete the ride if they are wrong.",
            actions = listOf(ReviewAction.KeepAsIs)
        )
    }
    val actions = candidates.mapIndexed { index, c ->
        ReviewAction.ConfirmCandidate(index, c.routeId, c.isExpress, "Confirm ${c.label}")
    }
    return RideReview(
        prompt = "Express or local? " + candidates.joinToString(" or ") { it.label } + " both serve this trip.",
        actions = actions + ReviewAction.KeepAsIs
    )
}

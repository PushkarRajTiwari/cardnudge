package com.cardnudge.domain

object Recommender {

    /** Best and second-best card for a category, in one pass with no intermediate lists. */
    fun best(cards: List<RewardCard>, category: Category): Pick? {
        var best: RewardCard? = null
        var bestRate = -1.0
        var second: RewardCard? = null
        var secondRate = -1.0
        for (card in cards) {
            val rate = card.rateFor(category)
            if (rate > bestRate) {
                second = best; secondRate = bestRate
                best = card; bestRate = rate
            } else if (rate > secondRate) {
                second = card; secondRate = rate
            }
        }
        return best?.let { Pick(it, bestRate, second, if (second == null) 0.0 else secondRate) }
    }

    fun arrivalAlert(store: Store, cards: List<RewardCard>): Alert {
        val pick = best(cards, store.category)
            ?: return Alert(store.id, "${store.category.emoji} You're at ${store.name}", "Add your cards in CardNudge to get a suggestion here.")
        val runner = pick.runnerUp?.let { " Next best: ${it.name} at ${it.reward(pick.runnerUpRate)}." }.orEmpty()
        return Alert(
            id = store.id,
            title = "${store.category.emoji} Use ${pick.card.name} at ${store.name}",
            body = "${pick.card.reward(pick.rate)} on ${store.category.label}.$runner",
        )
    }
}

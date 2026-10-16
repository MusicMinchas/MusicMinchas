package com.kike.musicplayer.data.subsonic

import org.junit.Assert.assertEquals
import org.junit.Test

class StarPlanTest {
    @Test
    fun firstSyncKeepsBothSides() {
        val plan = planStars(there = setOf("a", "b"), here = setOf("b", "c"), lastThere = null, lastHere = null)
        assertEquals(StarPlan(toFavourite = setOf("a"), toUnfavourite = emptySet(), toStar = setOf("c"), toUnstar = emptySet()), plan)
    }

    @Test
    fun changesOnEachSideGoToTheOther() {
        val last = setOf("a", "b")
        // Server: starred c, unstarred a. App: favourited d, unfavourited b.
        val plan = planStars(there = setOf("b", "c"), here = setOf("a", "d"), lastThere = last, lastHere = last)
        assertEquals(setOf("c"), plan.toFavourite)
        assertEquals(setOf("a"), plan.toUnfavourite)
        assertEquals(setOf("d"), plan.toStar)
        assertEquals(setOf("b"), plan.toUnstar)
    }

    @Test
    fun nothingChangedNothingToDo() {
        val s = setOf("a", "b")
        assertEquals(StarPlan(emptySet(), emptySet(), emptySet(), emptySet()), planStars(s, s, s, s))
    }

    @Test
    fun theSameChangeOnBothSidesIsNotUndone() {
        // a unstarred there and unfavourited here; c starred there and favourited here.
        val plan = planStars(there = setOf("b", "c"), here = setOf("b", "c"), lastThere = setOf("a", "b"), lastHere = setOf("a", "b"))
        assertEquals(StarPlan(emptySet(), emptySet(), emptySet(), emptySet()), plan)
    }

    @Test
    fun otherSourcesSwitchedOffLeavesTheirStars() {
        // x and y were stars for Drive favourites; the option is off, so they drop out of "here" and of
        // "last here" (as ServerCollections does), and nothing is unstarred.
        val plan = planStars(there = setOf("s", "x", "y"), here = setOf("s"), lastThere = setOf("s", "x", "y"), lastHere = setOf("s"))
        assertEquals(StarPlan(emptySet(), emptySet(), emptySet(), emptySet()), plan)
    }
}

package com.kike.musicplayer.data.discover

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Headlines and publishers from Google News, 14 October 2026. */
class MusicNewsTest {
    private fun item(title: String, site: String) = FeedItem(title, "https://$site/x", 0, null, null, "https://$site")

    @Test
    fun musicStoriesAboutGhostAreKept() {
        assertTrue(MusicNews.isClearlyMusic(item("Watch: Ghost's Tobias Forge Revives His Old Death Metal Band For New Movie", "metalinjection.net")))
        assertTrue(MusicNews.isClearlyMusic(item("Is Ghost's Tobias Forge secretly recording a new death metal project?", "www.loudersound.com")))
        assertTrue(MusicNews.isClearlyMusic(item("Paradise Lost: A gothic metal masterclass in Tokyo", "www.roppongirocks.com")))
        assertTrue(MusicNews.isClearlyMusic(item("Victoria K unleash new single ahead of Australian shows with Paradise Lost", "www.therockpit.net")))
    }

    @Test
    fun otherStoriesAreDropped() {
        assertFalse(MusicNews.isClearlyMusic(item("Ghost Pirates make the turn towards home opener", "www.wtoc.com")))
        assertFalse(MusicNews.isClearlyMusic(item("Midwest Access: Haunting with Hawk Ghost Tours 2026", "www.kttc.com")))
        assertFalse(MusicNews.isClearlyMusic(item("Bonnie Jo Campbell Recommends Her Favorite Literary Ghost Stories", "lithub.com")))
        assertFalse(MusicNews.isClearlyMusic(item("Interview With a Ghost", "memphismagazine.com")))
        assertFalse(MusicNews.isClearlyMusic(item("Yeon Sang-ho tackles AI, human abyss in grief-driven 'Paradise Lost'", "www.koreatimes.co.kr")))
        assertFalse(MusicNews.isClearlyMusic(item("Amazon Stops Meta's Muse at the Door", "www.wsj.com")))
    }

    @Test
    fun ambiguityComesFromTheShareOfMusicHeadlines() {
        val music = List(7) { item("Metallica announce 2027 stadium tour", "loudwire.com") }
        val other = List(3) { item("Metallica is at Sphere this week. Ticket prices are dropping fast", "nypost.com") }
        assertFalse(MusicNews.isAmbiguous(music + other))
        val ghosts = List(8) { item("Little Ghost will open another location", "www.freep.com") }
        assertTrue(MusicNews.isAmbiguous(ghosts + music.take(2)))
        assertFalse("too few headlines to judge", MusicNews.isAmbiguous(ghosts.take(3)))
    }

    @Test
    fun theBandNameMustStandAlone() {
        val kept = listOf(
            "Ghost" to "Is Ghost’s Tobias Forge secretly recording a new death metal project?",
            "Ghost" to "Watch: Ghost's Tobias Forge Revives His Old Death Metal Band For New Movie",
            "Ghost" to "Priest, featuring former band members of Ghost, release new single \"Minions\"",
            "Muse" to "Muse frontman says band met Mark Zuckerberg before handing over social handles",
            "Muse" to "Muse Announce ‘The Wow! Signal’ 2027 European Arena Tour",
            "Muse" to "Dead Poet Society Will Support Muse on Tour",
            "Paradise Lost" to "Paradise Lost: A gothic metal masterclass in Tokyo",
            "Paradise Lost" to "Victoria K Unleash New Single ‘Incubus’ Ahead of Australian Shows with Paradise Lost",
        )
        val dropped = listOf(
            "Ghost" to "'Ghost Song' Review: Fatih Akin's Thin but Pretty Supernatural Romance",
            "Ghost" to "Ghost Song: Fatih Akin’s haunting tale of love beyond death",
            "Ghost" to "Interview: SAVNT Lead Singer for Ghost Hounds",
            "Ghost" to "THE HAUNT Launch 2026 ‘Ghost’ Tour Ahead of Highly Anticipated Sophomore Album",
            "Ghost" to "Love Ghost Details Lucky New Album, ‘Thirteen’",
            "Ghost" to "Space Ghost – “Back From Reality”",
            "Muse" to "John Legend Details 'Muse,' Collaborative Album with Pharrell Williams",
            "Muse" to "NMIXX returns to Korea on Oct. 19 with mysterious mini album Strange Muse",
            "Paradise Lost" to "Paradise Lost Movie: Showtimes, Review, Songs, Trailer",
        )
        kept.forEach { (name, title) -> assertTrue(title, Names.standsAlone(title, name)) }
        dropped.forEach { (name, title) -> assertFalse(title, Names.standsAlone(title, name)) }
    }
}

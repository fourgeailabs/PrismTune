package com.fourgeailabs.prismtune

import com.fourgeailabs.prismtune.data.parseTagTitles
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5.4 server mood list parser tests. Fixtures only, never shown in the app. */
class ServerTagListTest {

    @Test
    fun directoryTitlesPassThroughDeduped() {
        val json = JSONObject(
            """{"MediaContainer":{"Directory":[
                {"title":"Acerbic"},{"title":"Acerbic"},
                {"title":"  Amiable & Good-Natured  "},{"title":""},
                {"title":"Angst-Ridden"}
            ]}}"""
        )
        assertEquals(
            listOf("Acerbic", "Amiable & Good-Natured", "Angst-Ridden"),
            parseTagTitles(json),
        )
    }

    @Test
    fun metadataTitlesAndTagFieldAreRead() {
        val json = JSONObject(
            """{"MediaContainer":{"Metadata":[
                {"title":"Happy"},{"tag":"Chill"}
            ]}}"""
        )
        assertEquals(listOf("Happy", "Chill"), parseTagTitles(json))
    }

    @Test
    fun missingContainerIsEmptyNotACrash() {
        assertTrue(parseTagTitles(JSONObject("{}")).isEmpty())
        assertTrue(parseTagTitles(JSONObject("""{"MediaContainer":{}}""")).isEmpty())
    }

    @Test
    fun caseInsensitiveDedupKeepsFirstSpelling() {
        val json = JSONObject(
            """{"MediaContainer":{"Directory":[
                {"title":"Happy"},{"title":"happy"},{"title":"HAPPY"}
            ]}}"""
        )
        assertEquals(listOf("Happy"), parseTagTitles(json))
    }
}

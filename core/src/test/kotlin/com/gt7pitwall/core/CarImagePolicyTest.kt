package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.CarImagePolicy
import com.gt7pitwall.core.knowledge.CarImagePolicy.Outcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CarImagePolicyTest {
    @Test
    fun theUrlUsesTheUdpCarCode() {
        // Course du 27/10 : carCode 3474 = Subaru BRZ GT300 '21 dans la base gt7info.
        assertEquals("https://www.gran-turismo.com/common/dist/gt7/carlist/car_thumbnails/car3474.png", CarImagePolicy.url(3474))
        // Deux voitures de la mise à jour 1.72, déjà dans cars.csv : Ferrari F355 (3654) et Citroën 2 CV (3653).
        assertEquals("https://www.gran-turismo.com/common/dist/gt7/carlist/car_thumbnails/car3654.png", CarImagePolicy.url(3654))
        assertEquals("https://www.gran-turismo.com/common/dist/gt7/carlist/car_thumbnails/car3653.png", CarImagePolicy.url(3653))
        assertNull(CarImagePolicy.url(0))
        assertNull(CarImagePolicy.url(99_999))
    }

    @Test
    fun redirectsStayOnTheOfficialSite() {
        assertTrue(CarImagePolicy.redirectAllowed("https://gran-turismo.com/common/dist/gt7/carlist/car_images/car3474.webp"))
        assertTrue(CarImagePolicy.redirectAllowed("https://static.gran-turismo.com/x.webp"))
        assertTrue(CarImagePolicy.redirectAllowed("/common/dist/gt7/carlist/car_images/car3474.webp"))
        assertFalse(CarImagePolicy.redirectAllowed("http://www.gran-turismo.com/x.webp"), "jamais en clair")
        assertFalse(CarImagePolicy.redirectAllowed("https://evil-gran-turismo.com/x.webp"))
        assertFalse(CarImagePolicy.redirectAllowed("//cdn.example.com/x.webp"))
        assertFalse(CarImagePolicy.redirectAllowed(null))
    }

    @Test
    fun theImageIsRecognisedByItsBytesNotItsAnnouncedType() {
        val webp = "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)
        assertEquals("webp", CarImagePolicy.imageFormat(webp))
        assertEquals("png", CarImagePolicy.imageFormat(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)))
        assertEquals("jpeg", CarImagePolicy.imageFormat(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertNull(CarImagePolicy.imageFormat("<html>".toByteArray()))
    }

    @Test
    fun everyFailureIsExplainedForTheWorkshop() {
        assertEquals("HTTP 403 pour la voiture 3474 : accès refusé par le site officiel — silhouette affichée", Outcome.Http(3474, 403).text)
        assertEquals("HTTP 404 pour la voiture 3474 : aucune image à cette adresse (chemin ou voiture absente du catalogue en ligne) — silhouette affichée", Outcome.Http(3474, 404).text)
        assertTrue("text/html" in Outcome.NotAnImage(3474, "text/html").text)
        assertEquals("photo officielle affichée (voiture 3474, 120 Ko)", Outcome.Shown(3474, 122_880, fromCache = false).text)
    }
}

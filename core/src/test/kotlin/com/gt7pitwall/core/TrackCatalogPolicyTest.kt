package com.gt7pitwall.core

import com.gt7pitwall.core.knowledge.TrackCatalogPolicy
import com.gt7pitwall.core.knowledge.TrackSignature
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lecture du catalogue officiel des circuits. Extraits SYNTHÉTIQUES reproduisant la forme relevée sur le site le 09/11
 * (aucun fichier de Sony / Polyphony dans le dépôt).
 */
class TrackCatalogPolicyTest {
    private val html = """<script type="module" crossorigin src="/common/dist/gt7/tracklist/assets/index-DE21iA-e.js"></script>"""
    private val index = """x=ad(Object.assign({"../../../generated-assets_tracks/images/track_logos/light/000971.png":()=>B(()=>import(`./000971-CVwbRXd_.js`),[]),""" +
        """"../../../generated-assets_tracks/images/track_logos/light/1d5980.png":()=>B(()=>import(`./1d5980-Abc_123.js`),[])}));""" +
        """y={"../generated-assets_tracks/data/catalog/tracks.fr.ts":()=>B(()=>import(`./tracks.fr-DLgGxkkn.js`),[])}"""
    private val catalog = """var e={"02e205":{baseId:`971481`,cornerCount:5,countryName:`États-Unis`,elevationGap:`0 m`,elevationGap_v:0,id:`02e205`,length:`1 200 m`,length_v:1200,nameBase:`X`,nameLong:`Circuit X`,straight:`2 000 m`,straight_v:2e3},""" +
        """c272b2:{baseId:`1d5980`,cornerCount:38,countryName:`France`,elevationGap:`37,3 m`,elevationGap_v:37.3,id:`c272b2`,length:`13 629 m`,length_v:13629,nameBase:`Circuit des 24 Heures du Mans`,nameLong:`Circuit des 24 Heures du Mans`,straight:`1 679 m`,straight_v:1679}};export{e as Tracks};"""

    @Test fun theSiteStructureIsReadWithoutFixedHashes() {
        assertEquals("https://www.gran-turismo.com/common/dist/gt7/tracklist/assets/index-DE21iA-e.js", TrackCatalogPolicy.indexScript(html))
        assertEquals("https://www.gran-turismo.com/common/dist/gt7/tracklist/assets/tracks.fr-DLgGxkkn.js", TrackCatalogPolicy.catalogModule(index))
        val logos = TrackCatalogPolicy.logoModules(index)
        assertEquals("https://www.gran-turismo.com/common/dist/gt7/tracklist/assets/1d5980-Abc_123.js", logos["1d5980"])
        assertEquals(2, logos.size)
        assertEquals(
            "https://www.gran-turismo.com/common/dist/gt7/tracklist/assets/000971-B75flT8v.png",
            TrackCatalogPolicy.logoPng("var e=`/common/dist/gt7/tracklist/assets/000971-B75flT8v.png`;export{e as default};"),
        )
    }

    @Test fun catalogEntriesKeepOfficialFacts() {
        val c = TrackCatalogPolicy.parseCatalog(catalog)
        val lm = c.getValue("c272b2")
        assertEquals("1d5980", lm.baseId)
        assertEquals("Circuit des 24 Heures du Mans", lm.name)
        assertEquals("France", lm.country)
        assertEquals(38, lm.corners)
        assertEquals(37.3, lm.elevationM)
        assertEquals(1679.0, lm.straightM)
        assertEquals(2000.0, c.getValue("02e205").straightM, "notation 2e3 lue")
    }

    @Test fun onlyOfficialAssetPathsAreAccepted() {
        assertNull(TrackCatalogPolicy.asset("../../etc/passwd"))
        assertNull(TrackCatalogPolicy.asset("https://evil.example/x.js"))
        assertNull(TrackCatalogPolicy.indexScript("""<script src="https://evil.example/index-x.js"></script>"""))
        assertTrue(!TrackCatalogPolicy.redirectAllowed("https://evil.example/x.png"))
    }

    @Test fun everySignatureCarriesItsOfficialId() {
        val sigs = TrackSignature.parseAll(File("../app/src/main/assets/gt7db/track_signatures.json").readText())
        assertTrue(sigs.isNotEmpty() && sigs.all { it.officialId?.length == 6 }, sigs.filter { it.officialId == null }.map { it.name }.toString())
        assertEquals("c272b2", sigs.first { it.name.startsWith("24 Heures") }.officialId)
    }
}

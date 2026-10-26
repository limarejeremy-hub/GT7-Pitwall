package com.gt7pitwall.core

import com.gt7pitwall.core.profile.Gt7CareerStats
import com.gt7pitwall.core.profile.Gt7CareerSample
import com.gt7pitwall.core.profile.Gt7CareerJson
import com.gt7pitwall.core.profile.Gt7CareerImport
import com.gt7pitwall.core.profile.Gt7PlayerProfile
import com.gt7pitwall.core.profile.ProfileEvidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Gt7CareerProfileTest {
    private val psn="PTIJEY27"

    @Test fun identityChangeNeverLeaksAnotherDriversHistory() {
        val mine = Gt7PlayerProfile().changeIdentity(psn)
            .add(Gt7CareerStats(dr="C",sr="S",drPoints=5313,races=71,wins=4),
                ProfileEvidence.USER_ENTERED,1000)
        assertEquals(1,mine.samples.size)
        assertEquals(0,mine.drPointsChange)
        val other=mine.changeIdentity("OtherPSN777")
        assertEquals("OtherPSN777",other.psnId)
        assertTrue(other.samples.isEmpty())
        assertNull(other.latest)
    }

    @Test fun snapshotsCalculateOnlyMeasuredChanges() {
        val a=Gt7PlayerProfile().changeIdentity(psn)
            .add(Gt7CareerStats(dr="C",sr="S",drPoints=5000,races=70,wins=3),
                ProfileEvidence.USER_ENTERED,1000)
            .add(Gt7CareerStats(dr="C",sr="S",drPoints=5313,races=71,wins=4),
                ProfileEvidence.USER_IMPORT,2000)
        assertEquals(313,a.drPointsChange)
        assertEquals(1,a.racesChange)
        assertEquals(2,a.samples.size)
        assertEquals(2,a.add(a.latest!!.stats,ProfileEvidence.USER_IMPORT,3000).samples.size)
        assertEquals(4.0/71.0,a.latest!!.stats.victoryRate)
    }

    @Test fun acceptsOnlySonyOfficialGt7ProfileLink() {
        assertTrue(Gt7PlayerProfile.validOfficialUrl(
            "https://www.gran-turismo.com/fr/gt7/user/mymenu/12345678-1234-1234-1234-123456789abc/profile/"))
        assertFalse(Gt7PlayerProfile.validOfficialUrl("https://gran-turismo.com.evil.test/fr/gt7/user/mymenu/a/profile/"))
        assertFalse(Gt7PlayerProfile.validOfficialUrl("http://www.gran-turismo.com/fr/gt7/user/mymenu/12345678-1234-1234-1234-123456789abc/profile/"))
        assertFalse(Gt7PlayerProfile.validOfficialUrl("https://www.gran-turismo.com@evil.test/fr/gt7/user/mymenu/12345678-1234-1234-1234-123456789abc/profile/"))
    }

    @Test fun importRejectsAnUnrelatedPsnIdAndInvalidNumbers() {
        val valid="""{"PSN_ID":"PTIJEY27","DR":"C","SR":"S","stats":{"dr_points":5313,"total_races":71,"victories":4,"poles":0}}"""
        val data=Gt7CareerImport.fromRacerJson(valid,psn)
        assertEquals(71,data.races)
        assertEquals(4,data.wins)
        assertEquals("S",data.sr)
        assertFailsWith<IllegalArgumentException> {
            Gt7CareerImport.fromRacerJson(valid,"SomebodyElse")
        }
        assertFailsWith<IllegalArgumentException> {
            Gt7CareerImport.fromRacerJson("""{"PSN_ID":"PTIJEY27","stats":{"total_races":7,"victories":9}}""",psn)
        }
    }

    @Test fun acceptsMultipleDriversOnlyWhenExactlyOneMatches() {
        val raw="""{"drivers":[{"PSN_ID":"OtherAccount","stats":{"total_races":100}},{"PSN_ID":"PTIJEY27","stats":{"total_races":71,"victories":4}}]}"""
        assertEquals(4,Gt7CareerImport.fromRacerJson(raw,psn).wins)
        assertFailsWith<IllegalArgumentException> {
            Gt7CareerImport.fromRacerJson("""{"drivers":[{"PSN_ID":"PTIJEY27","stats":{"total_races":1}},{"PSN_ID":"PTIJEY27","stats":{"total_races":2}}]}""",psn)
        }
    }

    @Test fun roundTripPreservesProvenanceWithoutAnySecretField() {
        val p=Gt7PlayerProfile().changeIdentity(psn)
            .add(Gt7CareerStats(dr="C",sr="S",drPoints=5313,races=71,wins=4),
                ProfileEvidence.USER_ENTERED,1000)
        val json=Gt7CareerJson.encode(p)
        assertFalse("npsso" in json.lowercase())
        assertFalse("password" in json.lowercase())
        val out=Gt7CareerJson.decode(json)
        assertEquals(p,out)
        assertEquals(1,out.samples.size)
        assertEquals(ProfileEvidence.USER_ENTERED,out.latest?.evidence)
    }

    @Test fun invalidInputNeverCreatesFictionalCareer() {
        assertEquals("Victoires supérieures aux courses",Gt7CareerStats(races=2,wins=3).validate())
        assertEquals(null,Gt7CareerStats(races=0,wins=0).victoryRate)
        assertFailsWith<IllegalArgumentException> { Gt7PlayerProfile().changeIdentity("a") }
    }
}

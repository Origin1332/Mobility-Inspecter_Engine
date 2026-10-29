package com.mobility.engine
import org.junit.Assert.*
import org.junit.Test

class CatalogSearchTest {
    private fun entry(id: String,number: String,name: String,relation: String="primary",cls: String="battery") =
        CatalogEntry(id,number,name,"BATTERY ASSY","2610","배터리 및 케이블","01","ISG","21'1229","","",
            "","","1",132,"130",listOf(131),listOf(45f,150f,552f,190f),595f,842f,mapOf(cls to relation))
    @Test fun numberSearchIgnoresSpacingAndHyphens() {
        val e=entry("a","268A036000","배터리 어셈블리")
        assertEquals(listOf(e),CatalogSearch.find(listOf(e),"battery","268-a036000"))
    }
    @Test fun relatedSearchDoesNotCrossPartGroups() {
        val battery=entry("a","1111","클램프-배터리","related")
        val other=entry("b","2222","클램프","related","air_cleaner")
        assertEquals(listOf(battery),CatalogSearch.find(listOf(battery,other),"battery","클램프"))
    }
    @Test fun primaryRowsFirstAndVariantsRemainCandidates() {
        val related=entry("a","1111","클램프","related")
        val first=entry("b","2222","배터리")
        val second=entry("c","3333","배터리")
        assertEquals(listOf(first,second,related),CatalogSearch.find(listOf(related,first,second),"battery",""))
    }
    @Test fun unknownPartIsEmpty() {
        assertTrue(CatalogSearch.find(listOf(entry("a","1111","배터리")),"unknown","").isEmpty())
    }
}

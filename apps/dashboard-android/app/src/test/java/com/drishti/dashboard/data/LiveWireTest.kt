package com.drishti.dashboard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveWireTest {
    @Test
    fun `snake case coordinator response maps to one live participant`() {
        val wire = MonitorJson.decodeFromString<LivePersonWire>(
            """
            {
              "id":"live-arun",
              "name":"Arun Kumar",
              "phone_number":"+91 00000 00000",
              "emergency_contact_name":"Not provided",
              "emergency_contact_number":"",
              "area":"The Hive, OMR, Chennai",
              "activity":"WALKING",
              "activity_detail":"chair detected",
              "last_update_ms":1000,
              "latitude":12.94,
              "longitude":80.23,
              "events":[{
                "id":"e1",
                "kind":"OBSTACLE_DETECTED",
                "at_ms":999,
                "detail":"chair detected centre"
              }]
            }
            """.trimIndent(),
        )

        val person = wire.toDomain()
        assertTrue(person.isLive)
        assertEquals(12.94, person.latitude!!, 0.0)
        assertEquals(SafetyEventKind.OBSTACLE_DETECTED, person.events.single().kind)
    }
}

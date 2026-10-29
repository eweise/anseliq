package anseliq.domain.model

import java.util.UUID

// A UUIDv7: time-ordered, so new rows land together in primary-key indexes. Real time, not the app
// clock, since a test moving the clock back would otherwise break that ordering.
def createId(): UUID = UUID.ofEpochMillis(System.currentTimeMillis())

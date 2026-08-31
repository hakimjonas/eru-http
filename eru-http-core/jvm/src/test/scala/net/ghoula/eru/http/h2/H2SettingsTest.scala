package net.ghoula.eru.http.h2

import munit.FunSuite

import net.ghoula.eru.http.TestHelpers.*

/** Verify that H2Settings.create with maxConcurrentStreams actually generates the SETTINGS entry.
  */
class H2SettingsTest extends FunSuite {

  test("Default settings do NOT include MaxConcurrentStreams in entries") {
    val settings = H2Settings.default
    val entries = settings.toEntries()
    val hasMaxConcurrent = entries.exists(_.id == H2Frame.SettingsParam.MaxConcurrentStreams)
    assertEquals(hasMaxConcurrent, false, "Default settings should not send MaxConcurrentStreams")
  }

  test("Settings with maxConcurrentStreams=100 DOES include MaxConcurrentStreams in entries") {
    val settings = H2Settings.create(maxConcurrentStreams = 100).assertSuccess
    val entries = settings.toEntries()
    val maxConcurrentEntry = entries.find(_.id == H2Frame.SettingsParam.MaxConcurrentStreams)
    assert(maxConcurrentEntry.isDefined, "Should include MaxConcurrentStreams entry")
    assertEquals(maxConcurrentEntry.get.value, 100)
  }

  test("Settings entries print for verification") {
    val settings = H2Settings.create(maxConcurrentStreams = 100).assertSuccess
    val entries = settings.toEntries()

    val found = entries.exists(e => e.id == H2Frame.SettingsParam.MaxConcurrentStreams && e.value == 100)
    assert(found, "Should include MaxConcurrentStreams=100 entry")
  }
}

package net.ghoula.eru.http.h2

import munit.FunSuite

import net.ghoula.eru.http.TestHelpers.*

/** Tests for HTTP/2 settings per RFC 9113 Section 6.5.2. */
class H2SettingsSpec extends FunSuite {

  // ============================================================================
  // Default Settings
  // ============================================================================

  test("Default settings have RFC-specified values") {
    val settings = H2Settings.default

    assertEquals(settings.headerTableSize, 4096)
    assertEquals(settings.enablePush, true)
    assertEquals(settings.maxConcurrentStreams, Int.MaxValue)
    assertEquals(settings.initialWindowSize, 65535)
    assertEquals(settings.maxFrameSize, 16384)
    assertEquals(settings.maxHeaderListSize, Int.MaxValue)
  }

  // ============================================================================
  // SETTINGS_HEADER_TABLE_SIZE (0x01)
  // ============================================================================

  test("Apply SETTINGS_HEADER_TABLE_SIZE") {
    val settings = H2Settings.default
    val entries = List(SettingsEntry(H2Frame.SettingsParam.HeaderTableSize, 8192))

    settings.apply(entries).assertSuccess
    assertEquals(settings.headerTableSize, 8192)
  }

  test("SETTINGS_HEADER_TABLE_SIZE accepts any value") {
    val settings = H2Settings.default
    val entries = List(SettingsEntry(H2Frame.SettingsParam.HeaderTableSize, 0))

    settings.apply(entries).assertSuccess
    assertEquals(settings.headerTableSize, 0)
  }

  // ============================================================================
  // SETTINGS_ENABLE_PUSH (0x02)
  // ============================================================================

  test("Apply SETTINGS_ENABLE_PUSH = 0 (disabled)") {
    val settings = H2Settings.default
    val entries = List(SettingsEntry(H2Frame.SettingsParam.EnablePush, 0))

    settings.apply(entries).assertSuccess
    assertEquals(settings.enablePush, false)
  }

  test("Apply SETTINGS_ENABLE_PUSH = 1 (enabled)") {
    val settings = H2Settings.default
    val entries = List(
      SettingsEntry(H2Frame.SettingsParam.EnablePush, 0), // First disable
      SettingsEntry(H2Frame.SettingsParam.EnablePush, 1) // Then enable
    )

    settings.apply(entries).assertSuccess
    assertEquals(settings.enablePush, true)
  }

  test("SETTINGS_ENABLE_PUSH rejects invalid value") {
    val settings = H2Settings.default
    val entries = List(SettingsEntry(H2Frame.SettingsParam.EnablePush, 2))

    val result = settings.apply(entries)
    assert(result.isFailure)
  }

  // ============================================================================
  // SETTINGS_MAX_CONCURRENT_STREAMS (0x03)
  // ============================================================================

  test("Apply SETTINGS_MAX_CONCURRENT_STREAMS") {
    val settings = H2Settings.default
    val entries = List(SettingsEntry(H2Frame.SettingsParam.MaxConcurrentStreams, 100))

    settings.apply(entries).assertSuccess
    assertEquals(settings.maxConcurrentStreams, 100)
  }

  // ============================================================================
  // SETTINGS_INITIAL_WINDOW_SIZE (0x04)
  // ============================================================================

  test("Apply SETTINGS_INITIAL_WINDOW_SIZE") {
    val settings = H2Settings.default
    val entries = List(SettingsEntry(H2Frame.SettingsParam.InitialWindowSize, 32768))

    settings.apply(entries).assertSuccess
    assertEquals(settings.initialWindowSize, 32768)
  }

  test("SETTINGS_INITIAL_WINDOW_SIZE rejects overflow (negative value)") {
    val settings = H2Settings.default
    // -1 in two's complement would be > Int.MaxValue as unsigned
    val entries = List(SettingsEntry(H2Frame.SettingsParam.InitialWindowSize, -1))

    val result = settings.apply(entries)
    assert(result.isFailure)
  }

  // ============================================================================
  // SETTINGS_MAX_FRAME_SIZE (0x05)
  // ============================================================================

  test("Apply SETTINGS_MAX_FRAME_SIZE at minimum (16384)") {
    val settings = H2Settings.default
    val entries = List(SettingsEntry(H2Frame.SettingsParam.MaxFrameSize, 16384))

    settings.apply(entries).assertSuccess
    assertEquals(settings.maxFrameSize, 16384)
  }

  test("Apply SETTINGS_MAX_FRAME_SIZE at maximum (16777215)") {
    val settings = H2Settings.default
    val entries = List(SettingsEntry(H2Frame.SettingsParam.MaxFrameSize, 16777215))

    settings.apply(entries).assertSuccess
    assertEquals(settings.maxFrameSize, 16777215)
  }

  test("SETTINGS_MAX_FRAME_SIZE rejects value below minimum") {
    val settings = H2Settings.default
    val entries = List(SettingsEntry(H2Frame.SettingsParam.MaxFrameSize, 16383))

    val result = settings.apply(entries)
    assert(result.isFailure)
  }

  test("SETTINGS_MAX_FRAME_SIZE rejects value above maximum") {
    val settings = H2Settings.default
    val entries = List(SettingsEntry(H2Frame.SettingsParam.MaxFrameSize, 16777216))

    val result = settings.apply(entries)
    assert(result.isFailure)
  }

  // ============================================================================
  // SETTINGS_MAX_HEADER_LIST_SIZE (0x06)
  // ============================================================================

  test("Apply SETTINGS_MAX_HEADER_LIST_SIZE") {
    val settings = H2Settings.default
    val entries = List(SettingsEntry(H2Frame.SettingsParam.MaxHeaderListSize, 65536))

    settings.apply(entries).assertSuccess
    assertEquals(settings.maxHeaderListSize, 65536)
  }

  // ============================================================================
  // Unknown Settings
  // ============================================================================

  test("Unknown settings are ignored") {
    val settings = H2Settings.default
    val entries = List(SettingsEntry(0x99, 12345)) // Unknown setting

    settings.apply(entries).assertSuccess
    // Should not throw, just ignore
  }

  // ============================================================================
  // Multiple Settings
  // ============================================================================

  test("Apply multiple settings at once") {
    val settings = H2Settings.default
    val entries = List(
      SettingsEntry(H2Frame.SettingsParam.HeaderTableSize, 8192),
      SettingsEntry(H2Frame.SettingsParam.MaxConcurrentStreams, 50),
      SettingsEntry(H2Frame.SettingsParam.InitialWindowSize, 131072)
    )

    settings.apply(entries).assertSuccess
    assertEquals(settings.headerTableSize, 8192)
    assertEquals(settings.maxConcurrentStreams, 50)
    assertEquals(settings.initialWindowSize, 131072)
  }

  // ============================================================================
  // Settings Creation
  // ============================================================================

  test("H2Settings.create validates maxFrameSize") {
    val result = H2Settings.create(maxFrameSize = 1000)
    assert(result.isFailure)
  }

  test("H2Settings.create validates initialWindowSize") {
    val result = H2Settings.create(initialWindowSize = -1)
    assert(result.isFailure)
  }

  test("H2Settings.server creates reasonable defaults") {
    val settings = H2Settings.server().assertSuccess
    assertEquals(settings.enablePush, false)
    assertEquals(settings.maxConcurrentStreams, 100)
  }

  test("H2Settings.client creates reasonable defaults") {
    val settings = H2Settings.client().assertSuccess
    assertEquals(settings.enablePush, false)
    assertEquals(settings.maxConcurrentStreams, 100)
  }

  // ============================================================================
  // toEntries
  // ============================================================================

  test("toEntries returns empty for default settings") {
    val settings = H2Settings.default
    val entries = settings.toEntries()
    assertEquals(entries, List.empty)
  }

  test("toEntries returns changed settings") {
    val settings = H2Settings.default
    settings
      .apply(
        List(
          SettingsEntry(H2Frame.SettingsParam.MaxConcurrentStreams, 50)
        )
      )
      .assertSuccess

    val entries = settings.toEntries()
    assertEquals(entries.length, 1)
    assertEquals(entries.head.id, H2Frame.SettingsParam.MaxConcurrentStreams)
    assertEquals(entries.head.value, 50)
  }

  // ============================================================================
  // Copy
  // ============================================================================

  test("copy creates independent settings") {
    val original = H2Settings.default
    original.apply(List(SettingsEntry(H2Frame.SettingsParam.MaxConcurrentStreams, 50))).assertSuccess

    val copied = original.copy()
    copied.apply(List(SettingsEntry(H2Frame.SettingsParam.MaxConcurrentStreams, 100))).assertSuccess

    assertEquals(original.maxConcurrentStreams, 50)
    assertEquals(copied.maxConcurrentStreams, 100)
  }
}

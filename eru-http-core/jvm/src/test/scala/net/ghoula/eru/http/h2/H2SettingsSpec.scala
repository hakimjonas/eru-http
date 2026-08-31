package net.ghoula.eru.http.h2

import munit.FunSuite

import net.ghoula.eru.http.TestHelpers.*

/** Tests for HTTP/2 settings per RFC 9113 Section 6.5.2.
  *
  * `H2Settings` is immutable: `withEntries` returns a new `H2Settings` rather than mutating in
  * place, so test assertions read the value returned by `withEntries`, not the original.
  *
  * The default values harden two RFC "no limit" settings (see H2Frame.DefaultSettings):
  * MaxConcurrentStreams = 128 matches the nginx default and prevents stream-exhaustion attacks;
  * MaxHeaderListSize = 64KB matches the RFC advisory limit and mitigates CVE-2024-27316.
  *
  * A negative SETTINGS_INITIAL_WINDOW_SIZE is rejected because in two's complement it would exceed
  * Int.MaxValue when treated as unsigned.
  *
  * The case-class `copy` method inherits `private` from the primary constructor, which is
  * intentional: it prevents callers from bypassing the `create` / `server` / `client` smart
  * constructors and their validation. Internal state transitions go through `withEntries`.
  */
class H2SettingsSpec extends FunSuite {

  test("Default settings have eru-http production-hardened values") {
    val settings = H2Settings.default

    assertEquals(settings.headerTableSize, 4096)
    assertEquals(settings.enablePush, true)
    assertEquals(settings.initialWindowSize, 65535)
    assertEquals(settings.maxFrameSize, 16384)
    assertEquals(settings.maxConcurrentStreams, 128)
    assertEquals(settings.maxHeaderListSize, 65536)
  }

  test("withEntries applies SETTINGS_HEADER_TABLE_SIZE") {
    val updated = H2Settings.default
      .withEntries(List(SettingsEntry(H2Frame.SettingsParam.HeaderTableSize, 8192)))
      .assertSuccess
    assertEquals(updated.headerTableSize, 8192)
  }

  test("SETTINGS_HEADER_TABLE_SIZE accepts any value") {
    val updated = H2Settings.default
      .withEntries(List(SettingsEntry(H2Frame.SettingsParam.HeaderTableSize, 0)))
      .assertSuccess
    assertEquals(updated.headerTableSize, 0)
  }

  test("withEntries applies SETTINGS_ENABLE_PUSH = 0 (disabled)") {
    val updated = H2Settings.default
      .withEntries(List(SettingsEntry(H2Frame.SettingsParam.EnablePush, 0)))
      .assertSuccess
    assertEquals(updated.enablePush, false)
  }

  test("withEntries applies SETTINGS_ENABLE_PUSH = 1 (enabled) after disable") {
    val updated = H2Settings.default
      .withEntries(
        List(
          SettingsEntry(H2Frame.SettingsParam.EnablePush, 0),
          SettingsEntry(H2Frame.SettingsParam.EnablePush, 1)
        )
      )
      .assertSuccess
    assertEquals(updated.enablePush, true)
  }

  test("SETTINGS_ENABLE_PUSH rejects invalid value") {
    val result = H2Settings.default.withEntries(List(SettingsEntry(H2Frame.SettingsParam.EnablePush, 2)))
    assert(result.isFailure)
  }

  test("withEntries applies SETTINGS_MAX_CONCURRENT_STREAMS") {
    val updated = H2Settings.default
      .withEntries(List(SettingsEntry(H2Frame.SettingsParam.MaxConcurrentStreams, 100)))
      .assertSuccess
    assertEquals(updated.maxConcurrentStreams, 100)
  }

  test("withEntries applies SETTINGS_INITIAL_WINDOW_SIZE") {
    val updated = H2Settings.default
      .withEntries(List(SettingsEntry(H2Frame.SettingsParam.InitialWindowSize, 32768)))
      .assertSuccess
    assertEquals(updated.initialWindowSize, 32768)
  }

  test("SETTINGS_INITIAL_WINDOW_SIZE rejects overflow (negative value)") {
    val result = H2Settings.default.withEntries(List(SettingsEntry(H2Frame.SettingsParam.InitialWindowSize, -1)))
    assert(result.isFailure)
  }

  test("withEntries applies SETTINGS_MAX_FRAME_SIZE at minimum (16384)") {
    val updated = H2Settings.default
      .withEntries(List(SettingsEntry(H2Frame.SettingsParam.MaxFrameSize, 16384)))
      .assertSuccess
    assertEquals(updated.maxFrameSize, 16384)
  }

  test("withEntries applies SETTINGS_MAX_FRAME_SIZE at maximum (16777215)") {
    val updated = H2Settings.default
      .withEntries(List(SettingsEntry(H2Frame.SettingsParam.MaxFrameSize, 16777215)))
      .assertSuccess
    assertEquals(updated.maxFrameSize, 16777215)
  }

  test("SETTINGS_MAX_FRAME_SIZE rejects value below minimum") {
    val result = H2Settings.default.withEntries(List(SettingsEntry(H2Frame.SettingsParam.MaxFrameSize, 16383)))
    assert(result.isFailure)
  }

  test("SETTINGS_MAX_FRAME_SIZE rejects value above maximum") {
    val result = H2Settings.default.withEntries(List(SettingsEntry(H2Frame.SettingsParam.MaxFrameSize, 16777216)))
    assert(result.isFailure)
  }

  test("withEntries applies SETTINGS_MAX_HEADER_LIST_SIZE") {
    val updated = H2Settings.default
      .withEntries(List(SettingsEntry(H2Frame.SettingsParam.MaxHeaderListSize, 65536)))
      .assertSuccess
    assertEquals(updated.maxHeaderListSize, 65536)
  }

  test("Unknown settings are ignored") {
    val updated = H2Settings.default
      .withEntries(List(SettingsEntry(0x99, 12345)))
      .assertSuccess
    assertEquals(updated, H2Settings.default)
  }

  test("withEntries applies multiple settings at once") {
    val updated = H2Settings.default
      .withEntries(
        List(
          SettingsEntry(H2Frame.SettingsParam.HeaderTableSize, 8192),
          SettingsEntry(H2Frame.SettingsParam.MaxConcurrentStreams, 50),
          SettingsEntry(H2Frame.SettingsParam.InitialWindowSize, 131072)
        )
      )
      .assertSuccess
    assertEquals(updated.headerTableSize, 8192)
    assertEquals(updated.maxConcurrentStreams, 50)
    assertEquals(updated.initialWindowSize, 131072)
  }

  test("withEntries does not mutate the original settings") {
    val original = H2Settings.default
    original
      .withEntries(List(SettingsEntry(H2Frame.SettingsParam.MaxConcurrentStreams, 999)))
      .assertSuccess
    assertEquals(original.maxConcurrentStreams, H2Settings.default.maxConcurrentStreams)
  }

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

  test("toEntries returns empty for default settings") {
    val entries = H2Settings.default.toEntries()
    assertEquals(entries, List.empty)
  }

  test("toEntries returns changed settings") {
    val updated = H2Settings.default
      .withEntries(List(SettingsEntry(H2Frame.SettingsParam.MaxConcurrentStreams, 50)))
      .assertSuccess
    val entries = updated.toEntries()
    assertEquals(entries.length, 1)
    assertEquals(entries.head.id, H2Frame.SettingsParam.MaxConcurrentStreams)
    assertEquals(entries.head.value, 50)
  }

  test("structural equality: two settings with identical fields are equal") {
    val a = H2Settings.default
    val b = H2Settings.default
    assertEquals(a, b)
    assertEquals(a.hashCode, b.hashCode)
  }
}

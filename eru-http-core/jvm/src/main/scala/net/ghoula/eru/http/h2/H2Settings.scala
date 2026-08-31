package net.ghoula.eru.http.h2

import net.ghoula.eru.*

/** HTTP/2 connection settings as defined in RFC 9113 Section 6.5.2.
  *
  * Immutable case class. Each endpoint holds its own `H2Settings` value and the peer's `H2Settings`
  * value. Mutation (e.g. applying incoming SETTINGS frames) is done by producing a new value and
  * storing it in an Eru `Ref[H2Settings]` on the connection.
  *
  * Validation is enforced by the smart constructors `create` / `server` / `client` and by
  * `withEntries`, which returns a typed `H2Error` when an incoming SETTINGS frame violates the
  * RFC-imposed ranges. Direct `new H2Settings(...)` is private; construction outside this module
  * must go through the smart constructors.
  *
  * @see
  *   [[https://www.rfc-editor.org/rfc/rfc9113#section-6.5.2 RFC 9113 Section 6.5.2]]
  */
final case class H2Settings private (
  headerTableSize: Int,
  enablePush: Boolean,
  maxConcurrentStreams: Int,
  initialWindowSize: Int,
  maxFrameSize: Int,
  maxHeaderListSize: Int
) {

  /** Apply a sequence of SETTINGS entries received from the peer. Returns a new, updated
    * `H2Settings` or fails with a typed `H2Error` on the first invalid entry. Pure — does not
    * mutate this instance.
    *
    * Unknown settings IDs are silently ignored per RFC 9113 §6.5.2 (and may be legitimately used by
    * peers for experiments or extensions).
    *
    * @param entries
    *   settings entries from the SETTINGS frame, applied in order
    * @return
    *   Eru effect that succeeds with the updated settings or fails with H2Error on an invalid entry
    */
  def withEntries(entries: List[SettingsEntry]): Eru[H2Error, H2Settings] =
    entries.foldLeft(Eru.succeed(this): Eru[H2Error, H2Settings]) { (acc, entry) =>
      acc.flatMap(_.applyEntry(entry.id, entry.value))
    }

  /** Apply a single SETTINGS entry. Returns a new `H2Settings` or a typed error. */
  private def applyEntry(id: Int, value: Int): Eru[H2Error, H2Settings] = id match {
    case H2Frame.SettingsParam.HeaderTableSize =>
      // Any value is valid per RFC 9113 §6.5.2.
      Eru.succeed(copy(headerTableSize = value))

    case H2Frame.SettingsParam.EnablePush =>
      if value != 0 && value != 1 then
        Eru.fail(H2Error.SettingsError(s"SETTINGS_ENABLE_PUSH must be 0 or 1, got $value"))
      else Eru.succeed(copy(enablePush = value == 1))

    case H2Frame.SettingsParam.MaxConcurrentStreams =>
      // Any value is valid per RFC 9113 §6.5.2.
      Eru.succeed(copy(maxConcurrentStreams = value))

    case H2Frame.SettingsParam.InitialWindowSize =>
      // Must not exceed 2^31-1. A negative Int here means the unsigned u32 was > Int.MaxValue.
      if value < 0 then Eru.fail(H2Error.SettingsError("SETTINGS_INITIAL_WINDOW_SIZE exceeds maximum (2^31-1)"))
      else Eru.succeed(copy(initialWindowSize = value))

    case H2Frame.SettingsParam.MaxFrameSize =>
      if value < H2Frame.MinMaxFrameSize then
        Eru.fail(
          H2Error.SettingsError(s"SETTINGS_MAX_FRAME_SIZE must be at least ${H2Frame.MinMaxFrameSize}, got $value")
        )
      else if value > H2Frame.MaxMaxFrameSize then
        Eru.fail(
          H2Error.SettingsError(s"SETTINGS_MAX_FRAME_SIZE must be at most ${H2Frame.MaxMaxFrameSize}, got $value")
        )
      else Eru.succeed(copy(maxFrameSize = value))

    case H2Frame.SettingsParam.MaxHeaderListSize =>
      // Any value is valid (advisory only).
      Eru.succeed(copy(maxHeaderListSize = value))

    case _ =>
      // Unknown settings must be ignored per RFC 9113 Section 6.5.2.
      Eru.succeed(this)
  }

  /** Serialize to a list of SETTINGS entries suitable for sending in a SETTINGS frame, including
    * only values that differ from the given defaults. Default args are the library defaults so a
    * no-arg call Just Works.
    */
  def toEntries(defaults: H2Settings = H2Settings.default): List[SettingsEntry] = {
    val b = List.newBuilder[SettingsEntry]
    if headerTableSize != defaults.headerTableSize then
      b += SettingsEntry(H2Frame.SettingsParam.HeaderTableSize, headerTableSize)
    if enablePush != defaults.enablePush then
      b += SettingsEntry(H2Frame.SettingsParam.EnablePush, if enablePush then 1 else 0)
    if maxConcurrentStreams != defaults.maxConcurrentStreams then
      b += SettingsEntry(H2Frame.SettingsParam.MaxConcurrentStreams, maxConcurrentStreams)
    if initialWindowSize != defaults.initialWindowSize then
      b += SettingsEntry(H2Frame.SettingsParam.InitialWindowSize, initialWindowSize)
    if maxFrameSize != defaults.maxFrameSize then b += SettingsEntry(H2Frame.SettingsParam.MaxFrameSize, maxFrameSize)
    if maxHeaderListSize != defaults.maxHeaderListSize then
      b += SettingsEntry(H2Frame.SettingsParam.MaxHeaderListSize, maxHeaderListSize)
    b.result()
  }
}

object H2Settings {

  /** Default settings per RFC 9113 Section 6.5.2, with eru-http production hardening (bounded
    * `maxConcurrentStreams` and `maxHeaderListSize` to mitigate stream-exhaustion and
    * CVE-2024-27316-class header-list attacks). Immutable singleton.
    */
  val default: H2Settings = new H2Settings(
    headerTableSize = H2Frame.DefaultSettings.HeaderTableSize,
    enablePush = H2Frame.DefaultSettings.EnablePush,
    maxConcurrentStreams = H2Frame.DefaultSettings.MaxConcurrentStreams,
    initialWindowSize = H2Frame.DefaultSettings.InitialWindowSize,
    maxFrameSize = H2Frame.DefaultSettings.MaxFrameSize,
    maxHeaderListSize = H2Frame.DefaultSettings.MaxHeaderListSize
  )

  /** Create settings with custom values, validating ranges.
    *
    * @return
    *   Eru effect with new settings or error if invalid
    */
  def create(
    headerTableSize: Int = H2Frame.DefaultSettings.HeaderTableSize,
    enablePush: Boolean = H2Frame.DefaultSettings.EnablePush,
    maxConcurrentStreams: Int = H2Frame.DefaultSettings.MaxConcurrentStreams,
    initialWindowSize: Int = H2Frame.DefaultSettings.InitialWindowSize,
    maxFrameSize: Int = H2Frame.DefaultSettings.MaxFrameSize,
    maxHeaderListSize: Int = H2Frame.DefaultSettings.MaxHeaderListSize
  ): Eru[H2Error, H2Settings] = {
    if maxFrameSize < H2Frame.MinMaxFrameSize || maxFrameSize > H2Frame.MaxMaxFrameSize then
      Eru.fail(
        H2Error.SettingsError(
          s"maxFrameSize must be between ${H2Frame.MinMaxFrameSize} and ${H2Frame.MaxMaxFrameSize}, got $maxFrameSize"
        )
      )
    else if initialWindowSize < 0 then Eru.fail(H2Error.SettingsError("initialWindowSize must not exceed 2^31-1"))
    else
      Eru.succeed(
        new H2Settings(
          headerTableSize = headerTableSize,
          enablePush = enablePush,
          maxConcurrentStreams = maxConcurrentStreams,
          initialWindowSize = initialWindowSize,
          maxFrameSize = maxFrameSize,
          maxHeaderListSize = maxHeaderListSize
        )
      )
  }

  /** Server-side settings with push disabled and reasonable limits. */
  def server(
    maxConcurrentStreams: Int = 100,
    maxFrameSize: Int = H2Frame.DefaultMaxFrameSize,
    initialWindowSize: Int = 65535
  ): Eru[H2Error, H2Settings] = create(
    enablePush = false, // Servers don't typically need push
    maxConcurrentStreams = maxConcurrentStreams,
    maxFrameSize = maxFrameSize,
    initialWindowSize = initialWindowSize
  )

  /** Client-side settings optimized for typical use. */
  def client(
    enablePush: Boolean = false, // Client can disable server push
    maxConcurrentStreams: Int = 100,
    maxFrameSize: Int = H2Frame.DefaultMaxFrameSize,
    initialWindowSize: Int = 65535
  ): Eru[H2Error, H2Settings] = create(
    enablePush = enablePush,
    maxConcurrentStreams = maxConcurrentStreams,
    maxFrameSize = maxFrameSize,
    initialWindowSize = initialWindowSize
  )
}

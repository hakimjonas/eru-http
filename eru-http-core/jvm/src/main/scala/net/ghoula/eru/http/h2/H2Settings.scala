package net.ghoula.eru.http.h2

import net.ghoula.eru.*

/** HTTP/2 connection settings as defined in RFC 9113 Section 6.5.2.
  *
  * Settings affect how endpoints communicate and are exchanged via SETTINGS frames. Each endpoint
  * maintains its own settings and the peer's settings.
  *
  * @see
  *   [[https://www.rfc-editor.org/rfc/rfc9113#section-6.5.2 RFC 9113 Section 6.5.2]]
  */
final class H2Settings private (
  private var _headerTableSize: Int,
  private var _enablePush: Boolean,
  private var _maxConcurrentStreams: Int,
  private var _initialWindowSize: Int,
  private var _maxFrameSize: Int,
  private var _maxHeaderListSize: Int
) {

  /** SETTINGS_HEADER_TABLE_SIZE (0x01): Maximum size of the HPACK dynamic table. Default: 4,096
    * bytes.
    */
  def headerTableSize: Int = _headerTableSize

  /** SETTINGS_ENABLE_PUSH (0x02): Whether server push is enabled. Default: true (enabled).
    */
  def enablePush: Boolean = _enablePush

  /** SETTINGS_MAX_CONCURRENT_STREAMS (0x03): Maximum number of concurrent streams. Default:
    * unlimited.
    */
  def maxConcurrentStreams: Int = _maxConcurrentStreams

  /** SETTINGS_INITIAL_WINDOW_SIZE (0x04): Initial window size for stream flow control. Default:
    * 65,535 bytes. Maximum: 2^31-1 bytes.
    */
  def initialWindowSize: Int = _initialWindowSize

  /** SETTINGS_MAX_FRAME_SIZE (0x05): Maximum frame payload size. Default: 16,384 bytes. Range:
    * 16,384 to 16,777,215 bytes.
    */
  def maxFrameSize: Int = _maxFrameSize

  /** SETTINGS_MAX_HEADER_LIST_SIZE (0x06): Maximum size of header list. Default: unlimited.
    */
  def maxHeaderListSize: Int = _maxHeaderListSize

  /** Apply settings from a SETTINGS frame.
    *
    * @param entries
    *   the settings entries from the frame
    * @return
    *   Eru effect that succeeds if all settings are valid or fails with H2Error
    */
  def apply(entries: List[SettingsEntry]): Eru[H2Error, Unit] = {
    entries.foldLeft(Eru.unit: Eru[H2Error, Unit]) { (acc, entry) =>
      acc.flatMap { _ =>
        applySetting(entry.id, entry.value)
      }
    }
  }

  /** Apply a single setting.
    *
    * @param id
    *   the settings parameter identifier
    * @param value
    *   the value to set
    * @return
    *   Eru effect that succeeds if valid or fails with H2Error
    */
  private def applySetting(id: Int, value: Int): Eru[H2Error, Unit] = {
    id match {
      case H2Frame.SettingsParam.HeaderTableSize =>
        // Any value is valid
        _headerTableSize = value
        Eru.unit

      case H2Frame.SettingsParam.EnablePush =>
        // Must be 0 or 1
        if value != 0 && value != 1 then {
          Eru.fail(H2Error.SettingsError(s"SETTINGS_ENABLE_PUSH must be 0 or 1, got $value"))
        } else {
          _enablePush = value == 1
          Eru.unit
        }

      case H2Frame.SettingsParam.MaxConcurrentStreams =>
        // Any value is valid
        _maxConcurrentStreams = value
        Eru.unit

      case H2Frame.SettingsParam.InitialWindowSize =>
        // Must not exceed 2^31-1 (Int.MaxValue)
        if value < 0 then {
          // Negative means > Int.MaxValue due to overflow
          Eru.fail(H2Error.SettingsError("SETTINGS_INITIAL_WINDOW_SIZE exceeds maximum (2^31-1)"))
        } else {
          _initialWindowSize = value
          Eru.unit
        }

      case H2Frame.SettingsParam.MaxFrameSize =>
        // Must be in range 2^14 (16384) to 2^24-1 (16777215)
        if value < H2Frame.MinMaxFrameSize then {
          Eru.fail(
            H2Error.SettingsError(s"SETTINGS_MAX_FRAME_SIZE must be at least ${H2Frame.MinMaxFrameSize}, got $value")
          )
        } else if value > H2Frame.MaxMaxFrameSize then {
          Eru.fail(
            H2Error.SettingsError(s"SETTINGS_MAX_FRAME_SIZE must be at most ${H2Frame.MaxMaxFrameSize}, got $value")
          )
        } else {
          _maxFrameSize = value
          Eru.unit
        }

      case H2Frame.SettingsParam.MaxHeaderListSize =>
        // Any value is valid (advisory only)
        _maxHeaderListSize = value
        Eru.unit

      case _ =>
        // Unknown settings must be ignored per RFC 9113 Section 6.5.2
        Eru.unit
    }
  }

  /** Create a list of settings entries for sending in a SETTINGS frame.
    *
    * @param defaults
    *   the default settings to compare against
    * @return
    *   list of settings that differ from defaults
    */
  def toEntries(defaults: H2Settings = H2Settings.default): List[SettingsEntry] = {
    val entries = scala.collection.mutable.ListBuffer[SettingsEntry]()

    if _headerTableSize != defaults._headerTableSize then {
      entries += SettingsEntry(H2Frame.SettingsParam.HeaderTableSize, _headerTableSize)
    }
    if _enablePush != defaults._enablePush then {
      entries += SettingsEntry(H2Frame.SettingsParam.EnablePush, if _enablePush then 1 else 0)
    }
    if _maxConcurrentStreams != defaults._maxConcurrentStreams then {
      entries += SettingsEntry(H2Frame.SettingsParam.MaxConcurrentStreams, _maxConcurrentStreams)
    }
    if _initialWindowSize != defaults._initialWindowSize then {
      entries += SettingsEntry(H2Frame.SettingsParam.InitialWindowSize, _initialWindowSize)
    }
    if _maxFrameSize != defaults._maxFrameSize then {
      entries += SettingsEntry(H2Frame.SettingsParam.MaxFrameSize, _maxFrameSize)
    }
    if _maxHeaderListSize != defaults._maxHeaderListSize then {
      entries += SettingsEntry(H2Frame.SettingsParam.MaxHeaderListSize, _maxHeaderListSize)
    }

    entries.toList
  }

  /** Create a copy of these settings. */
  def copy(): H2Settings = {
    new H2Settings(
      _headerTableSize,
      _enablePush,
      _maxConcurrentStreams,
      _initialWindowSize,
      _maxFrameSize,
      _maxHeaderListSize
    )
  }

  override def toString: String =
    s"H2Settings(headerTableSize=$_headerTableSize, enablePush=$_enablePush, " +
      s"maxConcurrentStreams=$_maxConcurrentStreams, initialWindowSize=$_initialWindowSize, " +
      s"maxFrameSize=$_maxFrameSize, maxHeaderListSize=$_maxHeaderListSize)"
}

object H2Settings {

  /** Default settings per RFC 9113 Section 6.5.2. */
  def default: H2Settings = new H2Settings(
    _headerTableSize = H2Frame.DefaultSettings.HeaderTableSize,
    _enablePush = H2Frame.DefaultSettings.EnablePush,
    _maxConcurrentStreams = H2Frame.DefaultSettings.MaxConcurrentStreams,
    _initialWindowSize = H2Frame.DefaultSettings.InitialWindowSize,
    _maxFrameSize = H2Frame.DefaultSettings.MaxFrameSize,
    _maxHeaderListSize = H2Frame.DefaultSettings.MaxHeaderListSize
  )

  /** Create settings with custom values.
    *
    * @param headerTableSize
    *   HPACK dynamic table size
    * @param enablePush
    *   whether server push is enabled
    * @param maxConcurrentStreams
    *   maximum concurrent streams
    * @param initialWindowSize
    *   initial flow control window size
    * @param maxFrameSize
    *   maximum frame payload size
    * @param maxHeaderListSize
    *   maximum header list size
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
    // Validate max frame size
    if maxFrameSize < H2Frame.MinMaxFrameSize || maxFrameSize > H2Frame.MaxMaxFrameSize then {
      Eru.fail(
        H2Error.SettingsError(
          s"maxFrameSize must be between ${H2Frame.MinMaxFrameSize} and ${H2Frame.MaxMaxFrameSize}, got $maxFrameSize"
        )
      )
    } else if initialWindowSize < 0 then {
      Eru.fail(H2Error.SettingsError("initialWindowSize must not exceed 2^31-1"))
    } else {
      Eru.succeed(
        new H2Settings(
          headerTableSize,
          enablePush,
          maxConcurrentStreams,
          initialWindowSize,
          maxFrameSize,
          maxHeaderListSize
        )
      )
    }
  }

  /** Server-side settings with push disabled and reasonable limits. */
  def server(
    maxConcurrentStreams: Int = 100,
    maxFrameSize: Int = H2Frame.DefaultMaxFrameSize,
    initialWindowSize: Int = 65535
  ): Eru[H2Error, H2Settings] = {
    create(
      enablePush = false, // Servers don't typically need push
      maxConcurrentStreams = maxConcurrentStreams,
      maxFrameSize = maxFrameSize,
      initialWindowSize = initialWindowSize
    )
  }

  /** Client-side settings optimized for typical use. */
  def client(
    enablePush: Boolean = false, // Client can disable server push
    maxConcurrentStreams: Int = 100,
    maxFrameSize: Int = H2Frame.DefaultMaxFrameSize,
    initialWindowSize: Int = 65535
  ): Eru[H2Error, H2Settings] = {
    create(
      enablePush = enablePush,
      maxConcurrentStreams = maxConcurrentStreams,
      maxFrameSize = maxFrameSize,
      initialWindowSize = initialWindowSize
    )
  }
}

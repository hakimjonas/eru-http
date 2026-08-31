package net.ghoula.eru.http.h2

/** HTTP/2 stream states as defined in RFC 9113 Section 5.1.
  *
  * Stream states control what frames can be sent and received on a stream.
  *
  * State diagram:
  * {{{
  *                             +--------+
  *                     send PP |        | recv PP
  *                    ,--------|  idle  |--------.
  *                   /         |        |         \
  *                  v          +--------+          v
  *           +----------+          |           +----------+
  *           |          |          | send H /  |          |
  *    ,------| reserved |          | recv H    | reserved |------.
  *    |      | (local)  |          |           | (remote) |      |
  *    |      +----------+          v           +----------+      |
  *    |          |             +--------+             |          |
  *    |          |     recv ES |        | send ES     |          |
  *    |   send H |     ,-------|  open  |-------.     | recv H   |
  *    |          |    /        |        |        \    |          |
  *    |          v   v         +--------+         v   v          |
  *    |      +----------+          |           +----------+      |
  *    |      |   half   |          |           |   half   |      |
  *    |      |  closed  |          | send R /  |  closed  |      |
  *    |      | (remote) |          | recv R    | (local)  |      |
  *    |      +----------+          |           +----------+      |
  *    |           |                |                 |           |
  *    |           | send ES /      |       recv ES / |           |
  *    |           | send R /       v        send R / |           |
  *    |           | recv R     +--------+   recv R   |           |
  *    | send R /  `----------->|        |<-----------'  send R / |
  *    | recv R                 | closed |               recv R   |
  *    `----------------------->|        |<-----------------------'
  *                             +--------+
  *
  *    send:   endpoint sends this frame
  *    recv:   endpoint receives this frame
  *
  *    H:  HEADERS frame (with implied CONTINUATION frames)
  *    PP: PUSH_PROMISE frame (with implied CONTINUATION frames)
  *    ES: END_STREAM flag
  *    R:  RST_STREAM frame
  * }}}
  *
  * @see
  *   [[https://www.rfc-editor.org/rfc/rfc9113#section-5.1 RFC 9113 Section 5.1]]
  */
enum H2StreamState {

  /** idle state - stream is not yet opened.
    *
    * Valid transitions:
    *   - Sending or receiving HEADERS -> open (or half-closed if END_STREAM set)
    *   - Sending PUSH_PROMISE -> reserved (local)
    *   - Receiving PUSH_PROMISE -> reserved (remote)
    */
  case Idle

  /** reserved (local) - stream reserved by local PUSH_PROMISE.
    *
    * Valid transitions:
    *   - Sending HEADERS -> half-closed (remote)
    *   - Sending/receiving RST_STREAM -> closed
    */
  case ReservedLocal

  /** reserved (remote) - stream reserved by remote PUSH_PROMISE.
    *
    * Valid transitions:
    *   - Receiving HEADERS -> half-closed (local)
    *   - Sending/receiving RST_STREAM -> closed
    */
  case ReservedRemote

  /** open state - both endpoints can send frames.
    *
    * Valid transitions:
    *   - Sending END_STREAM -> half-closed (local)
    *   - Receiving END_STREAM -> half-closed (remote)
    *   - Sending/receiving RST_STREAM -> closed
    */
  case Open

  /** half-closed (local) - local endpoint cannot send (except WINDOW_UPDATE, PRIORITY, RST_STREAM).
    *
    * Valid transitions:
    *   - Receiving END_STREAM -> closed
    *   - Sending/receiving RST_STREAM -> closed
    */
  case HalfClosedLocal

  /** half-closed (remote) - remote endpoint cannot send (except WINDOW_UPDATE, PRIORITY,
    * RST_STREAM).
    *
    * Valid transitions:
    *   - Sending END_STREAM -> closed
    *   - Sending/receiving RST_STREAM -> closed
    */
  case HalfClosedRemote

  /** closed state - stream is terminated.
    *
    * Per RFC 9113 Section 5.1, closed streams can still receive frames for a short period to handle
    * in-flight frames.
    */
  case Closed

  /** Check if the stream can send DATA frames. */
  def canSendData: Boolean = this match {
    case Open | HalfClosedRemote => true
    case _ => false
  }

  /** Check if the stream can receive DATA frames. */
  def canReceiveData: Boolean = this match {
    case Open | HalfClosedLocal => true
    case _ => false
  }

  /** Check if the stream can send HEADERS frames. */
  def canSendHeaders: Boolean = this match {
    case Idle | ReservedLocal | Open | HalfClosedRemote => true
    case _ => false
  }

  /** Check if the stream can receive HEADERS frames. */
  def canReceiveHeaders: Boolean = this match {
    case Idle | ReservedRemote | Open | HalfClosedLocal => true
    case _ => false
  }

  /** Check if the stream is open in either direction. */
  def isActive: Boolean = this match {
    case Open | HalfClosedLocal | HalfClosedRemote => true
    case _ => false
  }

  /** Check if the stream is fully closed. */
  def isClosed: Boolean = this == Closed

  /** Check if the stream is in a reserved state. */
  def isReserved: Boolean = this == ReservedLocal || this == ReservedRemote
}

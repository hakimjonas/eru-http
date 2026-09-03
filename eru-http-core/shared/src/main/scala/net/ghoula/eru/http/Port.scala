package net.ghoula.eru.http

import net.ghoula.eru.*

/** A validated TCP/UDP port number.
  *
  * This model restricts ports to 1-65535. Port 0 (the unspecified port) is a valid IANA value but
  * is not supported here.
  */
opaque type Port = Int

object Port {

  /** Enables `==` under `-language:strictEquality`. */
  given CanEqual[Port, Port] = CanEqual.derived

  /** Minimum valid port number. */
  val MinValue: Int = 1

  /** Maximum valid port number. */
  val MaxValue: Int = 65535

  /** HTTP protocol default port. */
  val HTTP: Port = 80

  /** HTTPS protocol default port. */
  val HTTPS: Port = 443

  /** Validates and creates a Port.
    *
    * @param value
    *   the port number to validate
    * @return
    *   a validated Port or an InvalidPort error
    */
  def apply(value: Int): Eru[InvalidPort, Port] = {
    if value < MinValue || value > MaxValue then {
      Eru.fail(InvalidPort(value, s"Port must be between $MinValue and $MaxValue"))
    } else {
      Eru.succeed(value)
    }
  }

  /** Parses a Port from a string.
    *
    * @param s
    *   the string to parse as a port number
    * @return
    *   a validated Port or an InvalidPort error
    */
  def parse(s: String): Eru[InvalidPort, Port] = {
    s.toIntOption match {
      case Some(n) => apply(n)
      case None => Eru.fail(InvalidPort(0, s"Not a valid port number: $s"))
    }
  }

  /** Unsafe constructor for compile-time constants.
    */
  private[http] def unsafeFromInt(value: Int): Port = value

  extension (port: Port) {

    /** Returns the numeric value of this port.
      *
      * @return
      *   the port number as an integer
      */
    def value: Int = port

    /** Checks if this is a well-known port (0-1023). These typically require root/admin privileges.
      *
      * @return
      *   true if the port is in the well-known range (0-1023)
      */
    def isWellKnown: Boolean = port < 1024

    /** Checks if this is a registered port (1024-49151). These are registered with IANA for
      * specific services.
      *
      * @return
      *   true if the port is in the registered range (1024-49151)
      */
    def isRegistered: Boolean = port >= 1024 && port <= 49151

    /** Checks if this is a dynamic/private port (49152-65535). These are used for ephemeral
      * connections.
      *
      * @return
      *   true if the port is in the dynamic range (49152-65535)
      */
    def isDynamic: Boolean = port >= 49152

    /** Checks if this port requires elevated privileges on Unix-like systems.
      *
      * @return
      *   true if the port requires elevated privileges (ports below 1024)
      */
    def requiresPrivileges: Boolean = port < 1024

    /** Returns the port category name.
      *
      * @return
      *   "well-known", "registered", or "dynamic" depending on the port range
      */
    def category: String = port match {
      case p if p < 1024 => "well-known"
      case p if p <= 49151 => "registered"
      case _ => "dynamic"
    }

    /** Common service name if known.
      *
      * @return
      *   the common service name associated with this port, or None if not recognized
      */
    def serviceName: Option[String] = port match {
      case 80 => Some("HTTP")
      case 443 => Some("HTTPS")
      case 22 => Some("SSH")
      case 21 => Some("FTP")
      case 25 => Some("SMTP")
      case 53 => Some("DNS")
      case 110 => Some("POP3")
      case 143 => Some("IMAP")
      case 389 => Some("LDAP")
      case 3306 => Some("MySQL")
      case 5432 => Some("PostgreSQL")
      case 27017 => Some("MongoDB")
      case 6379 => Some("Redis")
      case _ => None
    }

  }

  /** Error for invalid ports.
    */
  final case class InvalidPort(
    attempted: Int,
    reason: String
  ) {
    def message: String = s"Invalid port $attempted: $reason"
  }
}

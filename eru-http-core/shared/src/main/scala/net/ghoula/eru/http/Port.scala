package net.ghoula.eru.http

import net.ghoula.eru.*

/** A validated TCP/UDP port number.
  *
  * Port numbers range from 1 to 65535 as defined by IANA.
  */
opaque type Port = Int

object Port {

  /** Minimum valid port number. */
  val MinValue: Int = 1

  /** Maximum valid port number. */
  val MaxValue: Int = 65535

  /** Well-known ports (0-1023). These typically require elevated privileges to bind. */

  /** HTTP protocol default port. */
  val HTTP: Port = 80

  /** HTTPS protocol default port. */
  val HTTPS: Port = 443

  /** FTP (File Transfer Protocol) control port. */
  val FTP: Port = 21

  /** SSH (Secure Shell) protocol port. */
  val SSH: Port = 22

  /** Telnet protocol port. */
  val Telnet: Port = 23

  /** SMTP (Simple Mail Transfer Protocol) port. */
  val SMTP: Port = 25

  /** DNS (Domain Name System) port. */
  val DNS: Port = 53

  /** DHCP (Dynamic Host Configuration Protocol) server port. */
  val DHCP: Port = 67

  /** TFTP (Trivial File Transfer Protocol) port. */
  val TFTP: Port = 69

  /** POP3 (Post Office Protocol v3) port. */
  val POP3: Port = 110

  /** NNTP (Network News Transfer Protocol) port. */
  val NNTP: Port = 119

  /** NTP (Network Time Protocol) port. */
  val NTP: Port = 123

  /** IMAP (Internet Message Access Protocol) port. */
  val IMAP: Port = 143

  /** SNMP (Simple Network Management Protocol) port. */
  val SNMP: Port = 161

  /** LDAP (Lightweight Directory Access Protocol) port. */
  val LDAP: Port = 389

  /** SMB (Server Message Block) port. */
  val SMB: Port = 445

  /** SMTPS (SMTP over TLS/SSL) port. */
  val SMTPS: Port = 465

  /** LDAPS (LDAP over TLS/SSL) port. */
  val LDAPS: Port = 636

  /** Rsync protocol port. */
  val RSYNC: Port = 873

  /** IMAPS (IMAP over TLS/SSL) port. */
  val IMAPS: Port = 993

  /** POP3S (POP3 over TLS/SSL) port. */
  val POP3S: Port = 995

  /** Registered ports (1024-49151). These are registered with IANA for specific services. */

  /** MySQL database default port. */
  val MySQL: Port = 3306

  /** PostgreSQL database default port. */
  val PostgreSQL: Port = 5432

  /** MongoDB database default port. */
  val MongoDB: Port = 27017

  /** Redis database default port. */
  val Redis: Port = 6379

  /** Elasticsearch default HTTP port. */
  val Elasticsearch: Port = 9200

  /** Apache Kafka default port. */
  val Kafka: Port = 9092

  /** RabbitMQ default AMQP port. */
  val RabbitMQ: Port = 5672

  /** Common development ports for local testing and development servers. */

  /** Common development port (8000). */
  val Dev8000: Port = 8000

  /** Common development port (8080). */
  val Dev8080: Port = 8080

  /** Common development port (3000). */
  val Dev3000: Port = 3000

  /** Common development port (5000). */
  val Dev5000: Port = 5000

  /** Common development port (9000). */
  val Dev9000: Port = 9000

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
  ) extends Exception(s"Invalid port $attempted: $reason")
}

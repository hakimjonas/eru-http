package net.ghoula.eru.http

import net.ghoula.eru.*

/** HTTP URI as defined in RFC 3986.
  *
  * This is an opaque type ensuring only valid URIs can be constructed.
  */
opaque type Uri = Uri.Components

object Uri {

  /** Internal representation of URI components.
    */
  final case class Components(
    scheme: Option[String],
    authority: Option[Authority],
    path: String,
    query: Option[String],
    fragment: Option[String]
  )

  /** Authority component of a URI (user@host:port).
    *
    * @param userInfo
    *   optional user information (e.g., username)
    * @param host
    *   the hostname or IP address
    * @param port
    *   optional port number
    */
  final case class Authority(
    userInfo: Option[String],
    host: String,
    port: Option[Port]
  ) {

    /** Returns the string representation of this authority component.
      *
      * @return
      *   the authority as a string (e.g., "user@example.com:8080")
      */
    def value: String = {
      val userPart = userInfo.fold("")(_ + "@")
      val portPart = port.fold("")(p => ":" + p.value)
      s"$userPart$host$portPart"
    }
  }

  /** Creates a URI from components.
    *
    * @param scheme
    *   optional URI scheme (e.g., "http", "https")
    * @param authority
    *   optional authority component
    * @param path
    *   the path component
    * @param query
    *   optional query string
    * @param fragment
    *   optional fragment identifier
    * @return
    *   a constructed URI
    */
  def apply(
    scheme: Option[String] = None,
    authority: Option[Authority] = None,
    path: String = "",
    query: Option[String] = None,
    fragment: Option[String] = None
  ): Uri = Components(scheme, authority, path, query, fragment)

  /** Parses a URI string per RFC 3986.
    *
    * URI = scheme ":" hier-part [ "?" query ] [ "#" fragment ] hier-part = "//" authority
    * path-abempty / path-absolute / path-rootless / path-empty
    *
    * @param uri
    *   the URI string to parse
    * @return
    *   a parsed URI or an InvalidUri error
    */
  def parse(uri: String): Eru[InvalidUri, Uri] = {
    if uri.isEmpty then {
      Eru.fail(InvalidUri(uri, "URI cannot be empty"))
    } else {
      Eru.effect {
        var pos = 0
        val len = uri.length

        // Parse scheme (if present)
        // scheme = ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )
        var schemeOpt: Option[String] = None
        val colonIdx = uri.indexOf(':')
        if colonIdx > 0 then {
          val potentialScheme = uri.substring(0, colonIdx)
          if isValidScheme(potentialScheme) then {
            schemeOpt = Some(potentialScheme.toLowerCase)
            pos = colonIdx + 1
          }
        }

        // Parse authority (if present - starts with "//")
        var authorityOpt: Option[Authority] = None
        if pos < len - 1 && uri.charAt(pos) == '/' && uri.charAt(pos + 1) == '/' then {
          pos += 2
          val authorityStart = pos

          // Find end of authority (next /, ?, #, or end of string)
          while pos < len && uri.charAt(pos) != '/' && uri.charAt(pos) != '?' && uri.charAt(pos) != '#' do {
            pos += 1
          }

          val authorityStr = uri.substring(authorityStart, pos)
          authorityOpt = parseAuthority(authorityStr, uri) match {
            case Right(auth) => Some(auth)
            case Left(err) => throw err // Will be caught by mapError below
          }
        }

        // Parse path
        val pathStart = pos
        while pos < len && uri.charAt(pos) != '?' && uri.charAt(pos) != '#' do {
          pos += 1
        }
        val path = if pos > pathStart then uri.substring(pathStart, pos) else ""

        // Parse query (if present)
        var queryOpt: Option[String] = None
        if pos < len && uri.charAt(pos) == '?' then {
          pos += 1
          val queryStart = pos
          while pos < len && uri.charAt(pos) != '#' do {
            pos += 1
          }
          queryOpt = Some(uri.substring(queryStart, pos))
        }

        // Parse fragment (if present)
        var fragmentOpt: Option[String] = None
        if pos < len && uri.charAt(pos) == '#' then {
          pos += 1
          fragmentOpt = Some(uri.substring(pos))
        }

        Components(schemeOpt, authorityOpt, path, queryOpt, fragmentOpt)
      }.mapError {
        case e: InvalidUri => e
        case e: Throwable => InvalidUri(uri, Option(e.getMessage).getOrElse("Invalid URI"))
      }
    }
  }

  /** Validates a scheme per RFC 3986. scheme = ALPHA *( ALPHA / DIGIT / "+" / "-" / "." )
    */
  private def isValidScheme(s: String): Boolean = {
    s.nonEmpty && s.charAt(0).isLetter && s.forall(c => c.isLetterOrDigit || c == '+' || c == '-' || c == '.')
  }

  /** Parses authority component. authority = [ userinfo "@" ] host [ ":" port ]
    *
    * Returns Either for pure error handling within the Eru.effect block.
    */
  private def parseAuthority(authority: String, originalUri: String): Either[InvalidUri, Authority] = {
    if authority.isEmpty then {
      Left(InvalidUri(originalUri, "Authority cannot be empty"))
    } else {
      // Check for userinfo
      val atIdx = authority.indexOf('@')
      val (userInfoOpt, hostPortStr) = if atIdx >= 0 then {
        (Some(authority.substring(0, atIdx)), authority.substring(atIdx + 1))
      } else {
        (None, authority)
      }

      // Parse host and port
      val colonIdx = hostPortStr.lastIndexOf(':')
      val hostPortResult: Either[InvalidUri, (String, Option[Port])] = if colonIdx >= 0 then {
        val hostPart = hostPortStr.substring(0, colonIdx)
        val portStr = hostPortStr.substring(colonIdx + 1)

        // Try to parse port
        portStr.toIntOption match {
          case Some(portNum) if portNum >= 1 && portNum <= 65535 =>
            Right((hostPart, Some(Port.unsafeFromInt(portNum))))
          case Some(_) =>
            Left(InvalidUri(originalUri, s"Invalid port: $portStr (must be 1-65535)"))
          case None =>
            Left(InvalidUri(originalUri, s"Invalid port: $portStr (not a number)"))
        }
      } else {
        Right((hostPortStr, None))
      }

      hostPortResult.flatMap { case (host, portOpt) =>
        if host.isEmpty then {
          Left(InvalidUri(originalUri, "Host cannot be empty"))
        } else {
          Right(Authority(userInfoOpt, host, portOpt))
        }
      }
    }
  }

  /** Creates an HTTP URI.
    *
    * @param host
    *   the hostname
    * @param port
    *   optional port (defaults to 80)
    * @param path
    *   the path (defaults to "/")
    * @return
    *   an HTTP URI
    */
  def http(host: String, port: Option[Port] = None, path: String = "/"): Uri =
    Components(
      scheme = Some("http"),
      authority = Some(Authority(None, host, port.orElse(Some(Port.HTTP)))),
      path = path,
      query = None,
      fragment = None
    )

  /** Creates an HTTPS URI.
    *
    * @param host
    *   the hostname
    * @param port
    *   optional port (defaults to 443)
    * @param path
    *   the path (defaults to "/")
    * @return
    *   an HTTPS URI
    */
  def https(host: String, port: Option[Port] = None, path: String = "/"): Uri =
    Components(
      scheme = Some("https"),
      authority = Some(Authority(None, host, port.orElse(Some(Port.HTTPS)))),
      path = path,
      query = None,
      fragment = None
    )

  extension (uri: Uri) {
    def scheme: Option[String] = uri.scheme
    def authority: Option[Authority] = uri.authority
    def host: Option[String] = uri.authority.map(_.host)
    def port: Option[Port] = uri.authority.flatMap(_.port)
    def path: String = uri.path
    def query: Option[String] = uri.query
    def fragment: Option[String] = uri.fragment

    /** The string representation of this URI.
      */
    def value: String = {
      val schemePart = scheme.fold("")(_ + "://")
      val authorityPart = authority.fold("") { auth =>
        // Omit default ports
        val shouldOmitPort = (scheme, auth.port) match {
          case (Some("http"), Some(port)) if port.value == 80 => true
          case (Some("https"), Some(port)) if port.value == 443 => true
          case _ => false
        }

        if shouldOmitPort then {
          val userPart = auth.userInfo.fold("")(_ + "@")
          s"$userPart${auth.host}"
        } else {
          auth.value
        }
      }
      val queryPart = query.fold("")("?" + _)
      val fragmentPart = fragment.fold("")("#" + _)
      s"$schemePart$authorityPart$path$queryPart$fragmentPart"
    }

    /** Adds or replaces the path with validation.
      */
    def withPath(newPath: String): Eru[InvalidUri, Uri] = {
      if newPath.isEmpty then {
        Eru.fail(InvalidUri(newPath, "Path cannot be empty"))
      } else {
        val normalizedPath = if newPath.startsWith("/") then newPath else "/" + newPath
        Eru.succeed(uri.copy(path = normalizedPath))
      }
    }

    /** Adds a path segment with validation. Segment should not contain '/' characters.
      */
    def /(segment: String): Eru[InvalidUri, Uri] = {
      if segment.isEmpty then {
        Eru.fail(InvalidUri(segment, "Path segment cannot be empty"))
      } else if segment.contains("/") then {
        Eru.fail(InvalidUri(segment, "Path segment cannot contain '/' - use withPath for full paths"))
      } else {
        val newPath = if path.endsWith("/") then path + segment else path + "/" + segment
        Eru.succeed(uri.copy(path = newPath))
      }
    }

    /** Sets the query string directly (use withQueryParam for validated parameters).
      */
    def withQuery(newQuery: String): Uri =
      uri.copy(query = Some(newQuery))

    /** Adds a query parameter with validation. Keys and values are URL-encoded automatically.
      */
    def withQueryParam(key: String, value: String): Eru[InvalidUri, Uri] = {
      if key.isEmpty then {
        Eru.fail(InvalidUri(key, "Query parameter key cannot be empty"))
      } else {
        val encoded = s"${encode(key)}=${encode(value)}"
        val newQuery = uri.query match {
          case Some(existing) => s"$existing&$encoded"
          case None => encoded
        }
        Eru.succeed(uri.copy(query = Some(newQuery)))
      }
    }

    /** Removes the query string.
      */
    def withoutQuery: Uri = uri.copy(query = None)

    /** Sets the fragment.
      */
    def withFragment(newFragment: String): Uri =
      uri.copy(fragment = Some(newFragment))

    /** Removes the fragment.
      */
    def withoutFragment: Uri = uri.copy(fragment = None)

    /** Changes the scheme with validation. Common schemes: http, https, ftp, ws, wss
      */
    def withScheme(newScheme: String): Eru[InvalidUri, Uri] = {
      if newScheme.isEmpty then {
        Eru.fail(InvalidUri(newScheme, "Scheme cannot be empty"))
      } else if !newScheme.forall(c => c.isLetterOrDigit || c == '+' || c == '-' || c == '.') then {
        Eru.fail(InvalidUri(newScheme, "Scheme contains invalid characters"))
      } else {
        Eru.succeed(uri.copy(scheme = Some(newScheme.toLowerCase)))
      }
    }

    /** Changes the host with validation.
      */
    def withHost(newHost: String): Eru[InvalidUri, Uri] = {
      if newHost.isEmpty then {
        Eru.fail(InvalidUri(newHost, "Host cannot be empty"))
      } else {
        val newAuthority = uri.authority match {
          case Some(auth) => auth.copy(host = newHost)
          case None => Authority(None, newHost, None)
        }
        Eru.succeed(uri.copy(authority = Some(newAuthority)))
      }
    }

    /** Changes the port.
      */
    def withPort(newPort: Port): Uri = {
      val newAuthority = uri.authority match {
        case Some(auth) => auth.copy(port = Some(newPort))
        case None => Authority(None, "localhost", Some(newPort))
      }
      uri.copy(authority = Some(newAuthority))
    }

    /** Checks if this is an absolute URI.
      */
    def isAbsolute: Boolean = scheme.isDefined

    /** Checks if this is a relative URI.
      */
    def isRelative: Boolean = scheme.isEmpty

  }

  /** URL encodes a string per RFC 3986.
    *
    * Unreserved characters (A-Z, a-z, 0-9, -, ., _, ~) are not encoded. All other characters are
    * percent-encoded.
    */
  private def encode(s: String): String = {
    val result = new StringBuilder
    s.foreach { c =>
      if isUnreserved(c) then {
        result.append(c)
      } else {
        // Percent-encode the character's UTF-8 bytes
        val bytes = Bytes.fromString(c.toString, Charset.UTF8)
        var i = 0
        while i < bytes.length do {
          result.append('%')
          result.append(toHex((bytes.toArray(i) >> 4) & 0x0f))
          result.append(toHex(bytes.toArray(i) & 0x0f))
          i += 1
        }
      }
    }
    result.toString
  }

  /** Checks if a character is unreserved per RFC 3986. unreserved = ALPHA / DIGIT / "-" / "." / "_"
    * / "~"
    */
  private def isUnreserved(c: Char): Boolean =
    c.isLetterOrDigit || c == '-' || c == '.' || c == '_' || c == '~'

  /** Converts a nibble (0-15) to hex character.
    */
  private def toHex(n: Int): Char = {
    if n < 10 then ('0' + n).toChar
    else ('A' + (n - 10)).toChar
  }

  /** Error for invalid URIs.
    */
  final case class InvalidUri(
    value: String,
    reason: String,
    rfc: String = "RFC 3986"
  ) extends Exception(s"Invalid URI '$value': $reason ($rfc)")
}

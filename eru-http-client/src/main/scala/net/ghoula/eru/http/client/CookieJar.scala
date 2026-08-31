package net.ghoula.eru.http.client

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters.*

import net.ghoula.eru.*
import net.ghoula.eru.http.*

/** Cookie storage for HTTP clients following RFC 6265.
  *
  * A CookieJar stores cookies received from servers and provides them back in subsequent requests
  * according to domain and path matching rules.
  *
  * Implementations must be thread-safe for concurrent access.
  */
trait CookieJar {

  /** Adds a cookie to the jar for the given URI.
    *
    * The cookie's domain and path attributes will be used to determine which requests should
    * include this cookie. If the cookie has no domain, it will be associated with the URI's host.
    *
    * @param uri
    *   The URI from which the cookie was received
    * @param cookie
    *   The cookie to add
    * @return
    *   Success or an error
    */
  def add(uri: Uri, cookie: Cookie): Eru[HttpError, Unit]

  /** Retrieves all cookies that should be sent with a request to the given URI.
    *
    * This method applies domain matching and path matching rules per RFC 6265 to determine which
    * cookies are applicable. Expired cookies are filtered out.
    *
    * @param uri
    *   The URI for which to retrieve cookies
    * @return
    *   A list of cookies to include in the request
    */
  def getCookies(uri: Uri): Eru[HttpError, List[Cookie]]

  /** Removes a specific cookie from the jar.
    *
    * @param name
    *   The cookie name
    * @param domain
    *   The cookie domain (optional)
    * @param path
    *   The cookie path (optional)
    * @return
    *   Success or an error
    */
  def remove(name: String, domain: Option[String], path: Option[String]): Eru[HttpError, Unit]

  /** Removes all cookies from the jar.
    *
    * @return
    *   Success or an error
    */
  def clear(): Eru[HttpError, Unit]
}

object CookieJar {

  /** Creates an in-memory cookie jar.
    *
    * This implementation stores cookies in memory and is thread-safe for concurrent access using
    * ConcurrentHashMap.
    *
    * @return
    *   A new in-memory cookie jar
    */
  def inMemory: Eru[HttpError, CookieJar] =
    Eru.succeed(new InMemoryCookieJar)
}

/** In-memory implementation of CookieJar using ConcurrentHashMap for thread safety.
  *
  * Cookies are stored in a map keyed by (domain, path, name) to allow efficient lookup and removal.
  */
private[client] final class InMemoryCookieJar extends CookieJar {

  private val cookies = new ConcurrentHashMap[CookieKey, Cookie]()

  override def add(uri: Uri, cookie: Cookie): Eru[HttpError, Unit] = {
    Eru.effectTotal {
      val effectiveDomain = cookie.domain.getOrElse(uri.host.getOrElse(""))
      val effectivePath = cookie.path.getOrElse(defaultPath(uri.path))
      val normalizedCookie = cookie.copy(
        domain = Some(effectiveDomain),
        path = Some(effectivePath)
      )
      val key = CookieKey(effectiveDomain, effectivePath, cookie.name)
      cookies.put(key, normalizedCookie)
      ()
    }
  }

  override def getCookies(uri: Uri): Eru[HttpError, List[Cookie]] = {
    Eru.effectTotal {
      val now = Instant.now()
      val requestDomain = uri.host.getOrElse("")
      val requestPath = uri.path

      cookies.values().asScala.toList.filter { cookie =>
        !cookie.isExpired(now) &&
        cookie.domainMatches(requestDomain) &&
        cookie.pathMatches(requestPath)
      }
    }
  }

  override def remove(name: String, domain: Option[String], path: Option[String]): Eru[HttpError, Unit] = {
    Eru.effectTotal {
      domain match {
        case Some(d) =>
          path match {
            case Some(p) =>
              cookies.remove(CookieKey(d, p, name))
            case None =>
              val keysToRemove = cookies.keySet().asScala.filter { key =>
                key.domain == d && key.name == name
              }
              keysToRemove.foreach(cookies.remove)
          }
        case None =>
          val keysToRemove = cookies.keySet().asScala.filter(_.name == name)
          keysToRemove.foreach(cookies.remove)
      }
      ()
    }
  }

  override def clear(): Eru[HttpError, Unit] = {
    Eru.effectTotal {
      cookies.clear()
      ()
    }
  }

  /** Computes the default path for a cookie per RFC 6265 Section 5.1.4.
    *
    * Rules:
    *   1. If the uri-path is empty or does not start with "/", return "/"
    *   2. If the uri-path contains only a single "/", return "/"
    *   3. Return the uri-path up to (but not including) the last "/"
    */
  private def defaultPath(uriPath: String): String = {
    if uriPath.isEmpty || !uriPath.startsWith("/") then {
      "/"
    } else {
      val lastSlash = uriPath.lastIndexOf('/')
      if lastSlash == 0 then {
        "/"
      } else {
        uriPath.substring(0, lastSlash)
      }
    }
  }
}

/** Key for storing cookies in the jar.
  *
  * Cookies are uniquely identified by (domain, path, name) tuple.
  *
  * @param domain
  *   the cookie domain
  * @param path
  *   the cookie path
  * @param name
  *   the cookie name
  */
private[client] final case class CookieKey(
  domain: String,
  path: String,
  name: String
)

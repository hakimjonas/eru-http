package net.ghoula.eru.http.client

import munit.{FunSuite, Location}

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.{CountDownLatch, Executors}
import scala.concurrent.duration.*
import scala.concurrent.{Await, ExecutionContext, Future}

import net.ghoula.eru.*
import net.ghoula.eru.http.*

class CookieJarSpec extends FunSuite {

  // Test helpers
  extension [E, A](eru: Eru[E, A]) {
    def assertSuccess(using loc: Location): A = {
      eru.attempt.unsafeRunSync() match {
        case Result.Success(value) => value
        case Result.Failure(error) =>
          fail(s"Expected success but got failure: $error")(using loc)
      }
    }
  }

  // ===== Basic Add/Get Tests =====

  test("CookieJar - add and retrieve cookie") {
    val jar = CookieJar.inMemory.assertSuccess
    val uri = Uri.parse("http://example.com/").assertSuccess
    val cookie = Cookie("sessionId", "abc123")

    jar.add(uri, cookie).assertSuccess
    val cookies = jar.getCookies(uri).assertSuccess

    assertEquals(cookies.length, 1)
    assertEquals(cookies(0).name, "sessionId")
    assertEquals(cookies(0).value, "abc123")
  }

  test("CookieJar - add multiple cookies") {
    val jar = CookieJar.inMemory.assertSuccess
    val uri = Uri.parse("http://example.com/").assertSuccess
    val cookie1 = Cookie("sessionId", "abc123")
    val cookie2 = Cookie("userId", "xyz789")

    jar.add(uri, cookie1).assertSuccess
    jar.add(uri, cookie2).assertSuccess

    val cookies = jar.getCookies(uri).assertSuccess

    assertEquals(cookies.length, 2)
    assert(cookies.exists(_.name == "sessionId"))
    assert(cookies.exists(_.name == "userId"))
  }

  test("CookieJar - update cookie with same name") {
    val jar = CookieJar.inMemory.assertSuccess
    val uri = Uri.parse("http://example.com/").assertSuccess
    val cookie1 = Cookie("sessionId", "old_value")
    val cookie2 = Cookie("sessionId", "new_value")

    jar.add(uri, cookie1).assertSuccess
    jar.add(uri, cookie2).assertSuccess

    val cookies = jar.getCookies(uri).assertSuccess

    assertEquals(cookies.length, 1)
    assertEquals(cookies(0).value, "new_value")
  }

  // ===== Domain Matching Tests =====

  test("CookieJar - domain matching exact") {
    val jar = CookieJar.inMemory.assertSuccess
    val uri = Uri.parse("http://example.com/").assertSuccess
    val cookie = Cookie("id", "value", domain = Some("example.com"))

    jar.add(uri, cookie).assertSuccess

    val cookies = jar.getCookies(uri).assertSuccess
    assertEquals(cookies.length, 1)
  }

  test("CookieJar - domain matching subdomain") {
    val jar = CookieJar.inMemory.assertSuccess
    val setUri = Uri.parse("http://example.com/").assertSuccess
    val getUri = Uri.parse("http://sub.example.com/").assertSuccess
    val cookie = Cookie("id", "value", domain = Some("example.com"))

    jar.add(setUri, cookie).assertSuccess

    val cookies = jar.getCookies(getUri).assertSuccess
    assertEquals(cookies.length, 1)
  }

  test("CookieJar - domain matching does not match parent") {
    val jar = CookieJar.inMemory.assertSuccess
    val setUri = Uri.parse("http://sub.example.com/").assertSuccess
    val getUri = Uri.parse("http://example.com/").assertSuccess
    val cookie = Cookie("id", "value", domain = Some("sub.example.com"))

    jar.add(setUri, cookie).assertSuccess

    val cookies = jar.getCookies(getUri).assertSuccess
    assertEquals(cookies.length, 0)
  }

  test("CookieJar - domain matching different domain") {
    val jar = CookieJar.inMemory.assertSuccess
    val setUri = Uri.parse("http://example.com/").assertSuccess
    val getUri = Uri.parse("http://other.com/").assertSuccess
    val cookie = Cookie("id", "value", domain = Some("example.com"))

    jar.add(setUri, cookie).assertSuccess

    val cookies = jar.getCookies(getUri).assertSuccess
    assertEquals(cookies.length, 0)
  }

  test("CookieJar - uses host as default domain when cookie has no domain") {
    val jar = CookieJar.inMemory.assertSuccess
    val setUri = Uri.parse("http://example.com/").assertSuccess
    val getUriSame = Uri.parse("http://example.com/path").assertSuccess
    val getUriDifferent = Uri.parse("http://other.com/").assertSuccess
    val cookie = Cookie("id", "value") // No domain specified

    jar.add(setUri, cookie).assertSuccess

    val cookiesSame = jar.getCookies(getUriSame).assertSuccess
    assertEquals(cookiesSame.length, 1)

    val cookiesDifferent = jar.getCookies(getUriDifferent).assertSuccess
    assertEquals(cookiesDifferent.length, 0)
  }

  // ===== Path Matching Tests =====

  test("CookieJar - path matching exact") {
    val jar = CookieJar.inMemory.assertSuccess
    val uri = Uri.parse("http://example.com/docs").assertSuccess
    val cookie = Cookie("id", "value", path = Some("/docs"))

    jar.add(uri, cookie).assertSuccess

    val cookies = jar.getCookies(uri).assertSuccess
    assertEquals(cookies.length, 1)
  }

  test("CookieJar - path matching subpath") {
    val jar = CookieJar.inMemory.assertSuccess
    val setUri = Uri.parse("http://example.com/docs").assertSuccess
    val getUri = Uri.parse("http://example.com/docs/api").assertSuccess
    val cookie = Cookie("id", "value", path = Some("/docs"))

    jar.add(setUri, cookie).assertSuccess

    val cookies = jar.getCookies(getUri).assertSuccess
    assertEquals(cookies.length, 1)
  }

  test("CookieJar - path matching does not match different path") {
    val jar = CookieJar.inMemory.assertSuccess
    val setUri = Uri.parse("http://example.com/docs").assertSuccess
    val getUri = Uri.parse("http://example.com/api").assertSuccess
    val cookie = Cookie("id", "value", path = Some("/docs"))

    jar.add(setUri, cookie).assertSuccess

    val cookies = jar.getCookies(getUri).assertSuccess
    assertEquals(cookies.length, 0)
  }

  test("CookieJar - uses default path when cookie has no path") {
    val jar = CookieJar.inMemory.assertSuccess
    val setUri = Uri.parse("http://example.com/docs/page.html").assertSuccess
    val getUri = Uri.parse("http://example.com/docs/other.html").assertSuccess
    val cookie = Cookie("id", "value") // No path specified

    jar.add(setUri, cookie).assertSuccess

    // Default path should be /docs (up to last /)
    val cookies = jar.getCookies(getUri).assertSuccess
    assertEquals(cookies.length, 1)
  }

  test("CookieJar - default path for root URI") {
    val jar = CookieJar.inMemory.assertSuccess
    val setUri = Uri.parse("http://example.com/").assertSuccess
    val cookie = Cookie("id", "value") // No path specified

    jar.add(setUri, cookie).assertSuccess

    // Should be able to retrieve from any path
    val cookies1 = jar.getCookies(Uri.parse("http://example.com/").assertSuccess).assertSuccess
    val cookies2 = jar.getCookies(Uri.parse("http://example.com/docs").assertSuccess).assertSuccess

    assertEquals(cookies1.length, 1)
    assertEquals(cookies2.length, 1)
  }

  // ===== Expiration Tests =====

  test("CookieJar - filters out expired cookies (Max-Age)") {
    val jar = CookieJar.inMemory.assertSuccess
    val uri = Uri.parse("http://example.com/").assertSuccess
    val cookie = Cookie("id", "value", maxAge = Some(-1))

    jar.add(uri, cookie).assertSuccess

    val cookies = jar.getCookies(uri).assertSuccess
    assertEquals(cookies.length, 0)
  }

  test("CookieJar - filters out expired cookies (Expires)") {
    val jar = CookieJar.inMemory.assertSuccess
    val uri = Uri.parse("http://example.com/").assertSuccess
    val past = Instant.now().minus(1, ChronoUnit.DAYS)
    val cookie = Cookie("id", "value", expires = Some(past))

    jar.add(uri, cookie).assertSuccess

    val cookies = jar.getCookies(uri).assertSuccess
    assertEquals(cookies.length, 0)
  }

  test("CookieJar - includes non-expired cookies") {
    val jar = CookieJar.inMemory.assertSuccess
    val uri = Uri.parse("http://example.com/").assertSuccess
    val future = Instant.now().plus(1, ChronoUnit.DAYS)
    val cookie = Cookie("id", "value", expires = Some(future))

    jar.add(uri, cookie).assertSuccess

    val cookies = jar.getCookies(uri).assertSuccess
    assertEquals(cookies.length, 1)
  }

  // ===== Remove Tests =====

  test("CookieJar - remove cookie by name") {
    val jar = CookieJar.inMemory.assertSuccess
    val uri = Uri.parse("http://example.com/").assertSuccess
    val cookie = Cookie("sessionId", "abc123")

    jar.add(uri, cookie).assertSuccess
    jar.remove("sessionId", None, None).assertSuccess

    val cookies = jar.getCookies(uri).assertSuccess
    assertEquals(cookies.length, 0)
  }

  test("CookieJar - remove cookie by name and domain") {
    val jar = CookieJar.inMemory.assertSuccess
    val uri1 = Uri.parse("http://example.com/").assertSuccess
    val uri2 = Uri.parse("http://other.com/").assertSuccess
    val cookie1 = Cookie("id", "value1", domain = Some("example.com"))
    val cookie2 = Cookie("id", "value2", domain = Some("other.com"))

    jar.add(uri1, cookie1).assertSuccess
    jar.add(uri2, cookie2).assertSuccess

    jar.remove("id", Some("example.com"), None).assertSuccess

    val cookies1 = jar.getCookies(uri1).assertSuccess
    val cookies2 = jar.getCookies(uri2).assertSuccess

    assertEquals(cookies1.length, 0)
    assertEquals(cookies2.length, 1)
  }

  test("CookieJar - remove cookie by name, domain, and path") {
    val jar = CookieJar.inMemory.assertSuccess
    val uri = Uri.parse("http://example.com/docs").assertSuccess
    val cookie1 = Cookie("id", "value1", domain = Some("example.com"), path = Some("/docs"))
    val cookie2 = Cookie("id", "value2", domain = Some("example.com"), path = Some("/api"))

    jar.add(uri, cookie1).assertSuccess
    jar.add(uri, cookie2).assertSuccess

    jar.remove("id", Some("example.com"), Some("/docs")).assertSuccess

    val cookiesDocs = jar.getCookies(uri).assertSuccess
    val cookiesApi = jar.getCookies(Uri.parse("http://example.com/api").assertSuccess).assertSuccess

    assertEquals(cookiesDocs.length, 0)
    assertEquals(cookiesApi.length, 1)
  }

  // ===== Clear Tests =====

  test("CookieJar - clear all cookies") {
    val jar = CookieJar.inMemory.assertSuccess
    val uri1 = Uri.parse("http://example.com/").assertSuccess
    val uri2 = Uri.parse("http://other.com/").assertSuccess
    val cookie1 = Cookie("id1", "value1")
    val cookie2 = Cookie("id2", "value2")

    jar.add(uri1, cookie1).assertSuccess
    jar.add(uri2, cookie2).assertSuccess

    jar.clear().assertSuccess

    val cookies1 = jar.getCookies(uri1).assertSuccess
    val cookies2 = jar.getCookies(uri2).assertSuccess

    assertEquals(cookies1.length, 0)
    assertEquals(cookies2.length, 0)
  }

  // ===== Thread Safety Tests =====

  test("CookieJar - concurrent add operations") {
    val jar = CookieJar.inMemory.assertSuccess
    val uri = Uri.parse("http://example.com/").assertSuccess

    val numThreads = 10
    val cookiesPerThread = 100

    val executor = Executors.newFixedThreadPool(numThreads)
    given ExecutionContext = ExecutionContext.fromExecutor(executor)

    try {
      val latch = new CountDownLatch(numThreads)

      val futures = (0 `until` numThreads).map { threadId =>
        Future {
          (0 `until` cookiesPerThread).foreach { cookieId =>
            val cookie = Cookie(s"cookie-$threadId-$cookieId", s"value-$threadId-$cookieId")
            jar.add(uri, cookie).assertSuccess
          }
          latch.countDown()
        }
      }

      // Wait for all threads to complete
      Await.result(Future.sequence(futures), 10.seconds)

      val cookies = jar.getCookies(uri).assertSuccess
      assertEquals(cookies.length, numThreads * cookiesPerThread)
    } finally {
      executor.shutdown()
    }
  }

  test("CookieJar - concurrent add and get operations") {
    val jar = CookieJar.inMemory.assertSuccess
    val uri = Uri.parse("http://example.com/").assertSuccess

    val numWriters = 5
    val numReaders = 5
    val numOperations = 100

    val executor = Executors.newFixedThreadPool(numWriters + numReaders)
    given ExecutionContext = ExecutionContext.fromExecutor(executor)

    try {
      val latch = new CountDownLatch(numWriters + numReaders)

      // Writers
      val writerFutures = (0 `until` numWriters).map { writerId =>
        Future {
          (0 `until` numOperations).foreach { i =>
            val cookie = Cookie(s"cookie-$writerId", s"value-$i")
            jar.add(uri, cookie).assertSuccess
          }
          latch.countDown()
        }
      }

      // Readers
      val readerFutures = (0 `until` numReaders).map { _ =>
        Future {
          (0 `until` numOperations).foreach { _ =>
            jar.getCookies(uri).assertSuccess
          }
          latch.countDown()
        }
      }

      // Wait for all threads to complete
      Await.result(Future.sequence(writerFutures ++ readerFutures), 10.seconds)

      // Verify final state
      val cookies = jar.getCookies(uri).assertSuccess
      assertEquals(cookies.length, numWriters)
    } finally {
      executor.shutdown()
    }
  }

  // ===== Edge Cases =====

  test("CookieJar - getCookies returns empty list for unknown URI") {
    val jar = CookieJar.inMemory.assertSuccess
    val uri = Uri.parse("http://unknown.com/").assertSuccess

    val cookies = jar.getCookies(uri).assertSuccess
    assertEquals(cookies.length, 0)
  }

  test("CookieJar - remove non-existent cookie does not fail") {
    val jar = CookieJar.inMemory.assertSuccess

    jar.remove("nonexistent", None, None).assertSuccess
    // Should not throw
  }

  test("CookieJar - clear empty jar does not fail") {
    val jar = CookieJar.inMemory.assertSuccess

    jar.clear().assertSuccess
    // Should not throw
  }
}

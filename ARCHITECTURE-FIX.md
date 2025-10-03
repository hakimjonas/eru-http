# eru-http Architecture Fix: Eru-First Design

## The Fundamental Problem

We built eru-http like a traditional library that *happens* to use Eru, when it should be an **Eru application** through and through. This is a critical architectural misunderstanding.

## Current (WRONG) Architecture

```scala
// ❌ WRONG: Either-first with Eru as an afterthought
def parse(uri: String): Either[InvalidUri, Uri] = ???
def parseEru(uri: String): Eru[InvalidUri, Uri] = Eru.fromEither(parse(uri))

// ❌ WRONG: Pure functions with Eru extensions
final case class Request[A](...) {
  def validate: Either[InvalidRequest, Request[A]] = ???
}
// Then in package object...
extension [E, A](eru: Eru[E, Request[A]]) {
  def validateRequest: Eru[E | HttpError, Request[A]] = ???
}
```

## Correct (Eru-First) Architecture

```scala
// ✅ CORRECT: Eru is the ONLY API
object Uri {
  def parse(uri: String): Eru[InvalidUri, Uri] = {
    // Validation logic directly returns Eru
    if isValid(uri) then Eru.succeed(parseValid(uri))
    else Eru.fail(InvalidUri(uri, "..."))
  }
}

// ✅ CORRECT: All operations are effects
final case class Request[A](...) {
  def validate: Eru[InvalidRequest, Request[A]] = {
    for {
      _ <- validateMethodBodyCombination
      _ <- validateRequiredHeaders
      _ <- validateForbiddenHeaderCombinations
    } yield this
  }

  private def validateMethodBodyCombination: Eru[InvalidRequest, Unit] = {
    if !method.allowsRequestBody && body != EmptyBody then
      Eru.fail(InvalidRequest(s"Method ${method.value} does not allow a request body", "RFC 9110 Section 9"))
    else
      Eru.unit
  }
}

// ✅ CORRECT: Constructors return effects
object Headers {
  def empty: Headers = Headers(TreeMap.empty)

  def add(headers: Headers, name: String, value: String): Eru[InvalidHeader, Headers] = {
    for {
      headerName <- HeaderName.parse(name)
      headerValue <- HeaderValue.parse(value)
    } yield headers.unsafeAdd(headerName, headerValue)
  }
}
```

## Complete Redesign Needed

### 1. All Parsing/Validation Returns Eru

```scala
// BEFORE (Wrong)
object Method {
  def parse(value: String): Either[InvalidMethod, Method] = ???
}

// AFTER (Correct)
object Method {
  def parse(value: String): Eru[InvalidMethod, Method] = {
    if isValidToken(value) then
      Eru.succeed(value)
    else
      Eru.fail(InvalidMethod(value, "RFC 9110 Section 9.1: Method must be a valid token"))
  }

  // For constants, we can have pure values
  val GET: Method = "GET"  // These are pre-validated
  val POST: Method = "POST"
}
```

### 2. Builders Return Eru

```scala
// BEFORE (Wrong)
def addHeader(name: String, value: String): Request[A] =
  copy(headers = headers.add(name, value))

// AFTER (Correct)
def addHeader(name: String, value: String): Eru[InvalidHeader, Request[A]] = {
  for {
    newHeaders <- headers.add(name, value)
  } yield copy(headers = newHeaders)
}

// Or for a fluent API:
def withHeader(name: String, value: String): Eru[InvalidHeader, Request[A]] = {
  Headers.add(headers, name, value).map(h => copy(headers = h))
}
```

### 3. Smart Constructors Are Effects

```scala
// BEFORE (Wrong)
object Request {
  def get(uri: Uri): Request[EmptyBody] =
    Request(Method.GET, uri, Headers.empty, EmptyBody)
}

// AFTER (Correct)
object Request {
  def get(uri: String): Eru[InvalidUri, Request[EmptyBody]] = {
    for {
      parsedUri <- Uri.parse(uri)
    } yield Request(Method.GET, parsedUri, Headers.empty, EmptyBody)
  }

  // Pre-validated version for when you already have a Uri
  def getValid(uri: Uri): Request[EmptyBody] =
    Request(Method.GET, uri, Headers.empty, EmptyBody)
}
```

### 4. Effect-Native Validation

```scala
// BEFORE (Wrong)
trait Validator[A] {
  def validate(a: A): Either[ValidationError, A]
}

// AFTER (Correct)
trait Validator[A] {
  def validate(a: A): Eru[ValidationError, A]
}

// HTTP-specific validation
object HttpValidation {
  def validateRequest[A](req: Request[A]): Eru[HttpError, Request[A]] = {
    for {
      _ <- validateMethod(req.method)
      _ <- validateHeaders(req.headers)
      _ <- validateUri(req.uri)
      validatedReq <- req.validate
    } yield validatedReq
  }
}
```

### 5. Composition Through Effects

```scala
// Building a request becomes an effect chain
val request: Eru[HttpError, Request[String]] = for {
  uri <- Uri.parse("https://api.example.com/users")
  baseReq = Request.getValid(uri)
  reqWithAuth <- baseReq.withHeader("Authorization", "Bearer token")
  reqWithContent <- reqWithAuth.withHeader("Content-Type", "application/json")
  body = """{"name": "John"}"""
  finalReq = reqWithContent.withBody(body)
  validated <- finalReq.validate
} yield validated

// Or with error accumulation
val headers: Eru[InvalidHeader, Headers] = for {
  h1 <- Headers.empty.add("Content-Type", "application/json")
  h2 <- h1.add("Accept", "application/json")
  h3 <- h2.add("User-Agent", "eru-http/0.1.0")
} yield h3
```

## Why This Matters

### 1. Consistency with Eru Ecosystem
- Everything is an effect
- Errors flow through the Eru error channel
- Composability through monadic operations

### 2. Type Safety
- Invalid states caught at effect boundaries
- No surprise exceptions
- Clear error propagation

### 3. Following the Manifesto
- "Effect-native design" - not effects as an afterthought
- "Guided correctness" - the API guides users to correct usage
- "Foundational correctness" - validation happens in effects

## Migration Strategy

### Phase 1: Core Types (Immediate)
1. Change all `parse` methods to return `Eru[E, A]`
2. Remove all `Either` returns except for internal helpers
3. Make validation methods return Eru

### Phase 2: Builders (Next)
1. Make all builder methods return Eru when validation needed
2. Provide `unsafeX` methods that throw for testing/constants
3. Pre-validated constants remain pure

### Phase 3: Remove Either Completely
1. No public API should return Either
2. Internal methods can use Either if needed
3. All user-facing APIs are Eru-native

## Example: Complete Refactor of Uri

```scala
opaque type Uri = Uri.Components

object Uri {
  final case class Components(
    scheme: Option[String],
    authority: Option[Authority],
    path: String,
    query: Option[String],
    fragment: Option[String]
  )

  // Primary API - returns Eru
  def parse(uri: String): Eru[InvalidUri, Uri] = {
    Eru.effect {
      // Use Java URI for now, but this should be RFC 3986 parser
      val javaUri = new java.net.URI(uri)

      val authority = Option(javaUri.getHost).map { host =>
        Authority(
          userInfo = Option(javaUri.getUserInfo),
          host = host,
          port = if javaUri.getPort == -1 then None
                 else Some(Port.unsafeFromInt(javaUri.getPort))
        )
      }

      Components(
        scheme = Option(javaUri.getScheme),
        authority = authority,
        path = Option(javaUri.getPath).getOrElse(""),
        query = Option(javaUri.getQuery),
        fragment = Option(javaUri.getFragment)
      )
    }.mapError {
      case e: Exception => InvalidUri(uri, e.getMessage)
    }
  }

  // Builder that validates
  def http(host: String, path: String = "/"): Eru[InvalidUri, Uri] = {
    for {
      _ <- validateHost(host)
      _ <- validatePath(path)
    } yield Components(
      scheme = Some("http"),
      authority = Some(Authority(None, host, Some(Port.HTTP))),
      path = path,
      query = None,
      fragment = None
    )
  }

  // Pre-validated for constants
  def httpUnsafe(host: String, path: String = "/"): Uri = {
    Components(
      scheme = Some("http"),
      authority = Some(Authority(None, host, Some(Port.HTTP))),
      path = path,
      query = None,
      fragment = None
    )
  }

  extension (uri: Uri) {
    // These remain pure since they're just accessors
    def scheme: Option[String] = uri.scheme
    def host: Option[String] = uri.authority.map(_.host)

    // But modifications that could fail return Eru
    def withQueryParam(key: String, value: String): Eru[InvalidUri, Uri] = {
      for {
        validKey <- validateQueryKey(key)
        validValue <- validateQueryValue(value)
        encoded = s"${encode(validKey)}=${encode(validValue)}"
        newQuery = uri.query.fold(encoded)(q => s"$q&$encoded")
      } yield uri.copy(query = Some(newQuery))
    }
  }

  private def validateHost(host: String): Eru[InvalidUri, Unit] = {
    if host.isEmpty then
      Eru.fail(InvalidUri(host, "Host cannot be empty"))
    else
      Eru.unit
  }

  private def validatePath(path: String): Eru[InvalidUri, Unit] = {
    if !path.startsWith("/") then
      Eru.fail(InvalidUri(path, "Path must start with /"))
    else
      Eru.unit
  }
}
```

## Conclusion

eru-http needs to be **fundamentally restructured** as an Eru application, not a library with Eru integration. Every public API should work with Eru effects as the primary abstraction. This is not a "nice to have" - it's the entire point of the project.
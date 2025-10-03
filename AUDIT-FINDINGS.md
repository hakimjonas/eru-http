# eru-http Audit Findings

*October 2025 - Comprehensive code review for Eru manifesto compliance*

## Summary

The eru-http codebase is **well-structured with strong type safety**, but has gaps in validation, Eru integration, and RFC compliance. No Scala 2 habits were found - the code uses modern Scala 3 features correctly.

## Critical Issues (Must Fix)

### 1. ❌ Missing HeaderValue Validation
**Location**: Headers.scala:208-212
```scala
// Current - NO VALIDATION!
def apply(value: String): HeaderValue = value.trim
```
**Impact**: Violates RFC 9110 Section 5.5, breaks standards-first principle
**Fix**: Implement proper field-value validation

### 2. ❌ StatusCode.requiredHeaders Bug
**Location**: StatusCode.scala:176
```scala
case NotModified => Set("ETag", "Cache-Control", ...).intersect(Set.empty) // Always returns empty!
```
**Impact**: Required headers validation is broken for NotModified
**Fix**: Implement proper "at least one" requirement

### 3. ❌ URI Parser Incomplete
**Location**: Uri.scala:51-77
```scala
// Using java.net.URI - not RFC 3986 compliant
val url = new java.net.URI(uri)
```
**Impact**: Not fully RFC 3986 compliant
**Fix**: Implement proper URI parser or use compliant library

## High Priority Issues

### 4. ⚠️ Shallow Eru Integration
**Problem**: Core types use Either instead of Eru effects
```scala
// Current
def validate: Either[InvalidRequest, Request[A]]

// Should be
def validate: Eru[InvalidRequest, Request[A]]
```
**Impact**: Not following "effect-native design" principle
**Fix**: Add Eru-returning methods throughout

### 5. ⚠️ No Header Name Validation
**Problem**: Header names accept any string
```scala
headers.add("Invalid-!@#", "value") // Currently works!
```
**Impact**: Invalid HTTP can be created
**Fix**: Create HeaderName opaque type with validation

### 6. ⚠️ Inconsistent Error Handling
**Problem**: Some methods return Either, some throw, some return directly
```scala
def add(name: String, value: String): Headers // No error handling
def parse(s: String): Either[InvalidUri, Uri]  // Returns Either
Port.unsafeFromInt(999999) // Would create invalid Port
```
**Impact**: Inconsistent API, potential runtime errors
**Fix**: Consistent Either/Eru pattern with unsafe variants clearly marked

## Medium Priority Issues

### 7. 📝 Type Safety Improvements
- EmptyBody could use phantom types to prevent invalid combinations at compile time
- Port.unsafeFromInt is too accessible (private[http] instead of private)
- toException method encourages exception throwing

### 8. 📝 Code Quality
- MediaType parameter parsing could be cleaner with for-comprehensions
- Pattern matching could use @unchecked annotation where exhaustive
- Some validation is incomplete or has TODOs

## What's Working Well ✅

### Type Safety
- Excellent use of opaque types
- Invalid states mostly unrepresentable
- Rich semantic methods on types

### Modern Scala 3
- Proper if-then-else syntax throughout
- Using given instead of implicit
- Good use of extension methods
- Proper enum usage

### Standards Focus
- Good RFC documentation
- Error messages reference RFCs
- Semantic properties encoded in types

## Action Plan

### Phase 1: Critical Fixes
1. [ ] Implement HeaderValue validation per RFC 9110
2. [ ] Fix StatusCode.requiredHeaders for NotModified
3. [ ] Replace java.net.URI with RFC 3986 compliant parser

### Phase 2: Eru Integration
4. [ ] Add Eru-returning validation methods
5. [ ] Create Eru smart constructors for all types
6. [ ] Add HeaderName validation

### Phase 3: Type Safety
7. [ ] Implement phantom types for EmptyBody
8. [ ] Restrict unsafe constructors
9. [ ] Clean up error handling patterns

### Phase 4: Polish
10. [ ] Complete all validation TODOs
11. [ ] Improve code consistency
12. [ ] Add missing documentation

## Recommendations

### Immediate Changes

1. **Fix the critical bugs** - These break correctness:
```scala
// Fix StatusCode.requiredHeaders
case NotModified => Set.empty // Remove the .intersect(Set.empty)
```

2. **Add validation stubs** - Even if incomplete, structure for validation:
```scala
object HeaderValue {
  def apply(value: String): Either[InvalidHeaderValue, HeaderValue] = {
    // TODO: Full RFC 9110 Section 5.5 validation
    if value.trim.isEmpty then
      Left(InvalidHeaderValue(value, "Empty header value"))
    else
      Right(value.trim)
  }

  def unsafe(value: String): HeaderValue =
    apply(value).fold(throw _, identity)
}
```

3. **Standardize on Either with Eru extensions**:
```scala
// In every companion object
def parse(s: String): Either[InvalidType, Type] = ???
def parseEru(s: String): Eru[InvalidType, Type] =
  Eru.fromEither(parse(s))
def parseUnsafe(s: String): Type =
  parse(s).fold(throw _, identity)
```

### Long-term Architecture

Consider creating a validation framework pattern:
```scala
trait Validated[Raw, Valid, Error] {
  def validate(raw: Raw): Either[Error, Valid]
  def validateEru(raw: Raw): Eru[Error, Valid] =
    Eru.fromEither(validate(raw))
  def unsafe(raw: Raw): Valid =
    validate(raw).fold(throw _, identity)
}
```

This would standardize validation across all types.

## Conclusion

eru-http has a **solid foundation** with excellent type safety and modern Scala 3 usage. The main gaps are:

1. **Incomplete validation** (HeaderValue, HeaderName, URI)
2. **Shallow Eru integration** (using Either instead of Eru)
3. **Some critical bugs** (NotModified headers, URI parser)

None of these are architectural problems - they're all straightforward to fix. The codebase shows good understanding of both HTTP standards and functional programming principles.
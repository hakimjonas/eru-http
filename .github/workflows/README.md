# GitHub Actions Workflows

This directory contains automated workflows for eru-http.

## Workflows

### CI (ci.yml)
**Triggers:** Push to main/master, Pull Requests, Manual dispatch

Runs on every push and PR to ensure code quality:
- **Build Matrix**: Tests on Ubuntu, macOS, and Windows
- **Java Version**: 25 (Temurin distribution)
- **Scala Version**: 3.8.2
- **Steps**:
  1. Checkout code with full history
  2. Setup Java 25 with SBT caching
  3. Compile project with `sbt compile`
  4. Run tests with `sbt test`
  5. Check code formatting (Linux only)
  6. Generate coverage report (Linux only)
  7. Upload coverage to Codecov

**Additional Jobs**:
- **Lint**: Checks code formatting and runs Scalafix
- **Dependency Check**: Checks for dependency updates and vulnerabilities

### Pull Request (pr.yml)
**Triggers:** PR opened, synchronized, reopened

Provides detailed PR validation:
- **Validate**: Compiles, tests, and checks formatting
- **Size Check**: Warns if PR is >1000 lines
- **Comment**: Posts CI results summary to PR

### Release (release.yml)
**Triggers:** Version tags (v*.*.*), Manual dispatch

Handles release automation:
- **Validate**: Runs full test suite
- **Publish**: Publishes to Sonatype (requires secrets)
- **GitHub Release**: Creates release with installation instructions
- **Benchmark**: Runs performance benchmarks

## Required Secrets

For full functionality, configure these secrets in GitHub:

### Publishing (optional)
- `PGP_SECRET`: PGP private key for signing artifacts
- `PGP_PASSPHRASE`: Passphrase for PGP key
- `SONATYPE_USERNAME`: Sonatype/Maven Central username
- `SONATYPE_PASSWORD`: Sonatype/Maven Central password

### Coverage (optional)
- `CODECOV_TOKEN`: Token for Codecov.io

## Caching Strategy

Workflows use GitHub Actions cache to speed up builds:
- **SBT cache**: `~/.sbt`, `~/.ivy2/cache`, `~/.coursier/cache`
- **Cache key**: Based on OS, build files, and project configuration
- **Typical speedup**: 2-5x faster builds after first run

## Dependencies

eru-http depends on the Eru effect system, which is pulled from published releases:

```scala
libraryDependencies ++= Seq(
  "net.ghoula" %% "eru-core" % "0.1.0",
  "net.ghoula" %% "eru-runtime" % "0.1.0"
)
```

Eru artifacts are resolved from GitHub Packages or Maven Central, so no local Eru clone is needed for builds.

## Running Workflows Locally

### Using act (GitHub Actions local runner)

```bash
# Install act: https://github.com/nektos/act
brew install act  # macOS
# or: choco install act  # Windows
# or: sudo apt install act  # Linux

# Run CI workflow
act -W .github/workflows/ci.yml

# Run with specific event
act pull_request -W .github/workflows/pr.yml

# Run specific job
act -j build -W .github/workflows/ci.yml
```

### Manual Testing

```bash
# What CI does:
sbt +compile          # Compile for all Scala versions
sbt +test             # Run all tests
sbt scalafmtCheckAll  # Check formatting
sbt scalafmtSbtCheck  # Check build file formatting

# Fix formatting issues:
sbt scalafmtAll       # Format all source files
sbt scalafmtSbt       # Format build files
```

## Workflow Status

Check workflow status:
- [Actions Tab](../../actions)
- [CI Workflow](../../actions/workflows/ci.yml)
- [PR Workflow](../../actions/workflows/pr.yml)
- [Release Workflow](../../actions/workflows/release.yml)

## Troubleshooting

### Cache issues
If builds are unexpectedly slow:
1. Check cache hit rate in workflow logs
2. Clear cache from GitHub Actions settings
3. Verify cache key generation in workflow

### Formatting failures
```bash
# Locally fix formatting issues:
sbt scalafmtAll scalafmtSbt

# Check what would change:
sbt scalafmtCheck scalafmtSbtCheck
```

## Future Enhancements

- [ ] Add benchmark comparison against previous versions
- [ ] Add mutation testing
- [ ] Add property-based testing in CI
- [ ] Add security scanning (Snyk, Dependabot)
- [ ] Add deployment to GitHub Packages
- [ ] Add automatic changelog generation
- [ ] Add release notes from commits

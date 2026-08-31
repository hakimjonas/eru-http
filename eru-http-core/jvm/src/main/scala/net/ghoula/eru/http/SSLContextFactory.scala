package net.ghoula.eru.http

import java.io.FileInputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManager, X509TrustManager}

/** Factory for creating SSLContext instances from TlsConfig.
  *
  * Provides methods for creating client and server SSL contexts with proper configuration of trust
  * managers, key managers, and protocol versions.
  *
  * For clients:
  *   - Uses system trust store by default
  *   - Supports `trustAll` mode for development with self-signed certs
  *
  * For servers:
  *   - Requires keystore with server certificate and private key
  *   - Loads from PKCS12 or JKS keystore files
  */
object SSLContextFactory {

  /** Create an SSLContext for client connections.
    *
    * The Java SSLContext API requires null to mean "use defaults", so a null trust manager array
    * selects the system trust store, and a null key manager array selects the default key managers.
    *
    * @param config
    *   TLS configuration specifying protocols, trust settings, etc.
    * @return
    *   Configured SSLContext for client use
    * @throws Exception
    *   if context creation fails
    */
  def createClientContext(config: TlsConfig): SSLContext = {
    val context = SSLContext.getInstance(contextProtocol(config))

    // scalafix:off DisableSyntax.null
    val trustManagers: Array[TrustManager] | Null =
      if config.trustAll then Array(TrustAllTrustManager)
      else null

    context.init(null, trustManagers, SecureRandom.getInstanceStrong)
    // scalafix:on DisableSyntax.null
    context
  }

  /** Select the SSLContext protocol string based on configured protocols.
    *
    * SSLContext.getInstance("TLS") enables all TLS versions — this is too permissive when the
    * caller has restricted protocols. We pick the highest requested version; the per-engine
    * setProtocols() call (in SSLSocketChannel) does the final restriction. The "TLS" fallback is
    * only reached when protocols is empty; SSLSocketChannel still restricts per-engine.
    */
  private def contextProtocol(config: TlsConfig): String = {
    if config.protocols.contains(TlsVersion.TLSv1_3) then "TLSv1.3"
    else if config.protocols.contains(TlsVersion.TLSv1_2) then "TLSv1.2"
    else "TLS"
  }

  /** Create an SSLContext for server connections.
    *
    * A null trust manager array selects the default trust managers (system trust store), per the
    * SSLContext API convention.
    *
    * @param config
    *   TLS configuration specifying protocols, keystore path, etc.
    * @return
    *   Configured SSLContext for server use
    * @throws IllegalArgumentException
    *   if keyStorePath or keyStorePassword is not specified
    * @throws Exception
    *   if keystore loading or context creation fails
    */
  def createServerContext(config: TlsConfig): SSLContext = {
    val keyStorePath = config.keyStorePath.getOrElse(
      throw new IllegalArgumentException("keyStorePath is required for server TLS configuration")
    )
    val keyStorePassword = config.keyStorePassword.getOrElse(
      throw new IllegalArgumentException("keyStorePassword is required for server TLS configuration")
    )

    val keyStore = loadKeyStore(keyStorePath, keyStorePassword)

    val keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    keyManagerFactory.init(keyStore, keyStorePassword.toCharArray)

    val context = SSLContext.getInstance(contextProtocol(config))
    // scalafix:off DisableSyntax.null
    context.init(keyManagerFactory.getKeyManagers, null, SecureRandom.getInstanceStrong)
    // scalafix:on DisableSyntax.null
    context
  }

  /** Load a KeyStore from a file.
    *
    * Automatically detects keystore type based on file extension:
    *   - `.p12`, `.pfx` -> PKCS12
    *   - `.jks` -> JKS
    *   - Others -> PKCS12 (default)
    *
    * @param path
    *   Path to the keystore file
    * @param password
    *   Password for the keystore
    * @return
    *   Loaded KeyStore instance
    */
  private def loadKeyStore(path: String, password: String): KeyStore = {
    val storeType = path.toLowerCase match {
      case p if p.endsWith(".jks") => "JKS"
      case _ => "PKCS12"
    }

    val keyStore = KeyStore.getInstance(storeType)
    val inputStream = new FileInputStream(path)
    try {
      keyStore.load(inputStream, password.toCharArray)
    } finally {
      inputStream.close()
    }
    keyStore
  }

  /** Trust manager that accepts all certificates without validation.
    *
    * WARNING: This is insecure and should only be used for testing with self-signed certificates.
    * Never use in production!
    */
  private object TrustAllTrustManager extends X509TrustManager {
    override def checkClientTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
    override def checkServerTrusted(chain: Array[X509Certificate], authType: String): Unit = ()
    override def getAcceptedIssuers: Array[X509Certificate] = Array.empty
  }
}

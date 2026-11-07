package com.grabvr.manager

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import sun.security.x509.AlgorithmId
import sun.security.x509.CertificateAlgorithmId
import sun.security.x509.CertificateExtensions
import sun.security.x509.CertificateIssuerName
import sun.security.x509.CertificateSerialNumber
import sun.security.x509.CertificateSubjectName
import sun.security.x509.CertificateValidity
import sun.security.x509.CertificateVersion
import sun.security.x509.CertificateX509Key
import sun.security.x509.KeyIdentifier
import sun.security.x509.PrivateKeyUsageExtension
import sun.security.x509.SubjectKeyIdentifierExtension
import sun.security.x509.X500Name
import sun.security.x509.X509CertImpl
import sun.security.x509.X509CertInfo
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random

/** Holds the RSA key + certificate the headset trusts after pairing. Saved so pairing survives restarts. */
class AdbMgr(dir: File) : AbsAdbConnectionManager() {
    private val key: PrivateKey
    private val cert: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        val kf = File(dir, "adb_key.pk8")
        val cf = File(dir, "adb_cert.der")
        if (kf.exists() && cf.exists()) {
            key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(kf.readBytes()))
            cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(cf.readBytes()))
        } else {
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(2048, SecureRandom.getInstance("SHA1PRNG"))
            val pair = gen.generateKeyPair()
            val pub = pair.public
            val priv = pair.private

            val alg = "SHA512withRSA"
            val notBefore = Date()
            val notAfter = Date(System.currentTimeMillis() + 10L * 365 * 24 * 3600 * 1000)
            val ext = CertificateExtensions()
            ext.set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(pub).identifier))
            val name = X500Name("CN=GRABVR Manager")
            ext.set("PrivateKeyUsage", PrivateKeyUsageExtension(notBefore, notAfter))
            val info = X509CertInfo()
            info.set("version", CertificateVersion(2))
            info.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
            info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(alg)))
            info.set("subject", CertificateSubjectName(name))
            info.set("key", CertificateX509Key(pub))
            info.set("validity", CertificateValidity(notBefore, notAfter))
            info.set("issuer", CertificateIssuerName(name))
            info.set("extensions", ext)
            val c = X509CertImpl(info)
            c.sign(priv, alg)

            kf.writeBytes(priv.encoded)
            cf.writeBytes(c.encoded)
            key = priv
            cert = c
        }
    }

    override fun getPrivateKey(): PrivateKey = key
    override fun getCertificate(): Certificate = cert
    override fun getDeviceName(): String = "GRABVR Manager"
}

/** Wireless ADB to the headset the app is running on. */
class Adb(private val ctx: Context, private val store: Store) {
    private val mgr by lazy { AdbMgr(ctx.filesDir) }
    private val host = "127.0.0.1"
    @Volatile private var up = false

    /** pairPort + code come from Developer settings > Wireless debugging > Pair device with code. */
    fun pair(pairPort: Int, code: String, connectPort: Int?) {
        mgr.pair(host, pairPort, code)
        connect(connectPort)
    }

    fun reconnect() = connect(store.lastPort.takeIf { it > 0 })

    private fun connect(port: Int?) {
        up = false
        if (port != null && port > 0) {
            mgr.connect(host, port)
            store.lastPort = port
        } else {
            mgr.connectTls(ctx, 15000L) // finds the port by itself
        }
        up = true
        require(isUp()) { "Connected, but the headset did not answer." }
    }

    fun isUp(): Boolean {
        if (!up) return false
        return try {
            shell("echo ok").trim() == "ok"
        } catch (e: Throwable) {
            up = false
            false
        }
    }

    fun shell(cmd: String): String {
        require(up) { "Not connected. Pair wireless debugging in the Setup tab first." }
        return mgr.openStream("shell:$cmd").use { s ->
            String(s.openInputStream().readBytes(), Charsets.UTF_8)
        }
    }

    /** Writes a local file to a path on the headset. Binary safe (uses exec: without a pty). */
    fun push(file: File, remote: String) {
        require(up) { "Not connected. Pair wireless debugging in the Setup tab first." }
        val q = remote.replace("'", "'\\''")
        mgr.openStream("exec:head -c ${file.length()} > '$q'").use { s ->
            val out = s.openOutputStream()
            file.inputStream().use { it.copyTo(out) }
            out.flush()
            s.openInputStream().readBytes() // wait for the remote side to finish
        }
    }

    /** Streams an APK into the package installer. Returns its output ("Success" or a failure message). */
    fun installApk(file: File): String {
        require(up) { "Not connected. Pair wireless debugging in the Setup tab first." }
        return mgr.openStream("exec:cmd package install -S ${file.length()}").use { s ->
            val out = s.openOutputStream()
            file.inputStream().use { it.copyTo(out) }
            out.flush()
            String(s.openInputStream().readBytes(), Charsets.UTF_8)
        }
    }
}

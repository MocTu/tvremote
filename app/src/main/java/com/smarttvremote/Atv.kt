package com.smarttvremote

import android.annotation.SuppressLint
import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

class ApiError(val status: Int, val code: String, msg: String) : Exception(msg)
class WrongCode : Exception()

/** Chứng chỉ client tự ký, tạo một lần và lưu trong bộ nhớ riêng của app. TV nhớ chứng chỉ này sau khi ghép đôi. */
class CertStore(ctx: Context) {
    private val file = File(ctx.filesDir, "client.p12")
    private val pw = "tvremote".toCharArray()
    private val keyStore: KeyStore
    val clientPublicKey: RSAPublicKey

    init {
        val ks = KeyStore.getInstance("PKCS12")
        if (file.exists()) {
            try { FileInputStream(file).use { ks.load(it, pw) } } catch (e: Exception) { file.delete(); ks.load(null, null) }
        } else ks.load(null, null)
        if (!ks.containsAlias("client")) {
            val kpg = KeyPairGenerator.getInstance("RSA"); kpg.initialize(2048)
            val kp = kpg.generateKeyPair()
            val name = X500Name("CN=smarttvremote")
            val now = System.currentTimeMillis()
            val builder = JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), Date(now - 86_400_000L), Date(now + 20L * 365 * 86_400_000L), name, kp.public)
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
            val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
            ks.setKeyEntry("client", kp.private, pw, arrayOf(cert))
            FileOutputStream(file).use { ks.store(it, pw) }
        }
        keyStore = ks
        clientPublicKey = ks.getCertificate("client").publicKey as RSAPublicKey
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    val socketFactory: SSLSocketFactory by lazy {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, pw)
        // TV dùng chứng chỉ tự ký nên không kiểm tra chuỗi tin cậy; bảo vệ bằng việc chỉ kết nối IP mạng nội bộ do người dùng chọn.
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, arrayOf(tm), null)
        ctx.socketFactory
    }
}

private fun openTls(factory: SSLSocketFactory, host: String, port: Int, readTimeoutMs: Int): SSLSocket {
    val s = factory.createSocket() as SSLSocket
    try {
        s.connect(InetSocketAddress(host, port), 5000)
        s.soTimeout = readTimeoutMs
        s.startHandshake()
    } catch (e: Exception) { try { s.close() } catch (_: Exception) {}; throw e }
    return s
}

private fun stripZero(b: ByteArray): ByteArray {
    var i = 0
    while (i < b.size - 1 && b[i] == 0.toByte()) i++
    return b.copyOfRange(i, b.size)
}

/** Ghép đôi (cổng 6467): TV hiện mã 6 ký tự hex, người dùng nhập vào app. */
class PairingSession(private val host: String, private val factory: SSLSocketFactory, private val clientKey: RSAPublicKey) {
    private var socket: SSLSocket? = null
    private var serverKey: RSAPublicKey? = null

    private fun pm(field: Int, body: ByteArray) = Proto.W().int(1, 2).int(2, 200).bytes(field, body).toBytes()
    private fun send(p: ByteArray) { Proto.writeFrame(socket!!.outputStream, p) }
    private fun expect(): Map<Int, List<Any>> {
        val raw = Proto.readFrame(socket!!.inputStream) ?: throw ApiError(502, "pair_failed", "TV đã đóng kết nối ghép đôi.")
        val m = Proto.parse(raw)
        if (Proto.long(m, 2) != 200L) throw ApiError(502, "pair_failed", "TV từ chối ghép đôi.")
        return m
    }

    /** Chạy các bước bắt tay; khi xong TV đang hiện mã trên màn hình. */
    fun begin() {
        val s = openTls(factory, host, 6467, 15000)
        socket = s
        serverKey = s.session.peerCertificates[0].publicKey as RSAPublicKey
        send(pm(10, Proto.W().str(1, "androidtvremote2").str(2, "Smart TV Remote").toBytes()))
        expect() // pairing_request_ack
        val enc = Proto.W().int(1, 3).int(2, 6).toBytes() // HEXADECIMAL, 6 ký tự
        send(pm(20, Proto.W().bytes(1, enc).int(3, 1).toBytes())) // options: preferred_role = INPUT
        expect() // options của TV
        send(pm(30, Proto.W().bytes(1, enc).int(2, 1).toBytes())) // configuration: client_role = INPUT
        expect() // configuration_ack
    }

    fun finish(code: String) {
        val codeBytes = ByteArray(3) { code.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val sk = serverKey ?: throw ApiError(409, "not_pairing", "Chưa bắt đầu ghép đôi.")
        val md = MessageDigest.getInstance("SHA-256")
        md.update(stripZero(clientKey.modulus.toByteArray()))
        md.update(stripZero(clientKey.publicExponent.toByteArray()))
        md.update(stripZero(sk.modulus.toByteArray()))
        md.update(stripZero(sk.publicExponent.toByteArray()))
        md.update(codeBytes.copyOfRange(1, 3))
        val digest = md.digest()
        if (digest[0] != codeBytes[0]) throw WrongCode() // sai mã: có thể nhập lại, chưa gửi gì cho TV
        send(pm(40, Proto.W().bytes(1, digest).toBytes()))
        expect() // secret_ack
    }

    fun close() { try { socket?.close() } catch (_: Exception) {} }
}

/** Phiên điều khiển (cổng 6466). */
class RemoteSession(private val host: String, private val factory: SSLSocketFactory, private val onClosed: (RemoteSession) -> Unit) {
    private var socket: SSLSocket? = null
    private val readyLatch = CountDownLatch(1)
    private val writeLock = Any()
    @Volatile var isReady = false
    @Volatile private var closed = false

    /** Kết nối và đợi TV sẵn sàng. Trả về false nếu TV đóng kết nối (chưa ghép đôi) hoặc quá giờ. */
    fun start(timeoutMs: Long): Boolean {
        val s = openTls(factory, host, 6466, 20000) // TV ping mỗi ~5 giây; 20 giây không có gì = mất kết nối
        socket = s
        thread(isDaemon = true, name = "atv-read") { readLoop(s) }
        readyLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return isReady
    }

    private fun readLoop(s: SSLSocket) {
        try {
            val inp = s.inputStream.buffered()
            while (!closed) {
                val raw = Proto.readFrame(inp) ?: break
                val m = Proto.parse(raw)
                when {
                    m.containsKey(1) -> { // remote_configure của TV -> trả lời cấu hình của client
                        val dev = Proto.W().str(1, "Smart TV Remote").str(2, "Android").int(3, 1).str(4, "1")
                            .str(5, "androidtv-remote").str(6, "1.0.0").toBytes()
                        send(Proto.W().bytes(1, Proto.W().int(1, 622).bytes(2, dev).toBytes()).toBytes())
                    }
                    m.containsKey(2) -> { // remote_set_active
                        send(Proto.W().bytes(2, Proto.W().int(1, 622).toBytes()).toBytes())
                        isReady = true; readyLatch.countDown()
                    }
                    m.containsKey(8) -> { // ping
                        val v = Proto.long(Proto.sub(m, 8) ?: emptyMap(), 1) ?: 0L
                        send(Proto.W().bytes(9, Proto.W().int(1, v).toBytes()).toBytes())
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            close(); readyLatch.countDown(); onClosed(this)
        }
    }

    private fun send(p: ByteArray) {
        synchronized(writeLock) { Proto.writeFrame(socket!!.outputStream, p) }
    }

    fun key(code: Int) { send(Proto.W().bytes(10, Proto.W().int(1, code.toLong()).int(2, 3).toBytes()).toBytes()) } // direction SHORT
    fun appLink(url: String) { send(Proto.W().bytes(90, Proto.W().str(1, url).toBytes()).toBytes()) }

    fun close() { closed = true; isReady = false; try { socket?.close() } catch (_: Exception) {} }
}

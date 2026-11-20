package com.girag.nfcbridge

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.TagLostException
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.nfc.tech.NfcA
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Puente AppSheet -> NFC.
 *
 * Se abre con:  giragnfc://grabar?doc=VALOR_DOC&pwd=VALOR_PWD
 * Escribe `doc` como registro NDEF de texto y, si `pwd` no está vacío,
 * protege la etiqueta NTAG213/215/216 contra escritura con contraseña.
 */
class MainActivity : AppCompatActivity() {

    private enum class State { NO_DOC, WAITING, WRITING, SUCCESS, ERROR, NFC_OFF, NO_NFC }

    private lateinit var tvDoc: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvDetail: TextView
    private lateinit var statusBox: View
    private lateinit var progress: ProgressBar
    private lateinit var btnClose: Button
    private lateinit var btnNfcSettings: Button

    private var nfcAdapter: NfcAdapter? = null
    private var doc = ""
    private var pwd = ""
    private var state = State.NO_DOC

    private val busy = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { closeAndReturn() }

    // ---------------------------------------------------------------- Ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvDoc = findViewById(R.id.tvDoc)
        tvStatus = findViewById(R.id.tvStatus)
        tvDetail = findViewById(R.id.tvDetail)
        statusBox = findViewById(R.id.statusBox)
        progress = findViewById(R.id.progress)
        btnClose = findViewById(R.id.btnClose)
        btnNfcSettings = findViewById(R.id.btnNfcSettings)

        btnClose.setOnClickListener { closeAndReturn() }
        btnNfcSettings.setOnClickListener { startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        enableNfcListening()
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onDestroy() {
        ui.removeCallbacks(autoClose)
        worker.shutdown()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Solo guardamos los deep links; los intents de tag no deben re-procesarse
        if (intent.action == Intent.ACTION_VIEW) setIntent(intent)
        handleIntent(intent)
    }

    // ---------------------------------------------------------------- Intents

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> loadFromDeepLink(intent.data)
            NfcAdapter.ACTION_TAG_DISCOVERED,
            NfcAdapter.ACTION_TECH_DISCOVERED,
            NfcAdapter.ACTION_NDEF_DISCOVERED -> extractTag(intent)?.let { onTagDetected(it) }
            else -> if (doc.isEmpty()) showNoDocument()
        }
    }

    private fun loadFromDeepLink(uri: Uri?) {
        ui.removeCallbacks(autoClose)
        if (uri == null ||
            !uri.scheme.equals("giragnfc", ignoreCase = true) ||
            !uri.host.equals("grabar", ignoreCase = true)
        ) {
            doc = ""
            pwd = ""
            tvDoc.text = "—"
            showError("Enlace no válido", "Formato esperado:\ngiragnfc://grabar?doc=...&pwd=...")
            return
        }
        // getQueryParameter ya decodifica %XX
        doc = uri.getQueryParameter("doc")?.trim().orEmpty()
        pwd = uri.getQueryParameter("pwd")?.trim().orEmpty()

        if (doc.length > MAX_DOC_CHARS || pwd.length > MAX_PWD_CHARS) {
            doc = ""
            pwd = ""
            tvDoc.text = "—"
            showError("Enlace no válido", "El documento o la contraseña exceden el tamaño permitido.")
            return
        }

        if (doc.isEmpty()) {
            showNoDocument()
            return
        }
        tvDoc.text = doc
        if (nfcAdapter?.isEnabled == true) showWaiting()
    }

    @Suppress("DEPRECATION")
    private fun extractTag(intent: Intent): Tag? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }

    // ---------------------------------------------------------------- NFC

    private fun enableNfcListening() {
        val adapter = nfcAdapter
        if (adapter == null) {
            showNoNfc()
            return
        }
        if (!adapter.isEnabled) {
            showNfcDisabled()
            return
        }
        if (state == State.NFC_OFF) {
            if (doc.isNotEmpty()) showWaiting() else showNoDocument()
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags
        )
        // filtros null = recibir cualquier etiqueta mientras la app está al frente
        adapter.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    private fun onTagDetected(tag: Tag) {
        if (state == State.SUCCESS) return
        if (doc.isEmpty()) {
            showNoDocument()
            return
        }
        if (!busy.compareAndSet(false, true)) return

        val docToWrite = doc
        val pwdToWrite = pwd
        showWriting()

        worker.execute {
            val error: String? = try {
                TagWriter.write(tag, docToWrite, pwdToWrite)
                null
            } catch (e: TagLostException) {
                "Se perdió el contacto con la etiqueta. Manténgala quieta sobre el teléfono."
            } catch (e: NfcWriteException) {
                e.message
            } catch (e: Exception) {
                "Error de comunicación: ${e.message ?: e.javaClass.simpleName}"
            }

            ui.post {
                busy.set(false)
                if (isFinishing || isDestroyed) return@post
                if (error == null) {
                    // La contraseña no debe quedar en memoria ni en el intent tras grabar
                    pwd = ""
                    setIntent(Intent())
                    showSuccess(pwdToWrite.isNotEmpty())
                } else {
                    showError("No se pudo grabar", "$error\n\nAcerque la etiqueta de nuevo para reintentar.")
                }
            }
        }
    }

    // ---------------------------------------------------------------- UI

    private fun showNoDocument() =
        render(State.NO_DOC, COLOR_GREY, "Sin documento",
            "Abra esta pantalla desde el botón de AppSheet.")

    private fun showWaiting() =
        render(State.WAITING, COLOR_BLUE, "Acerque la etiqueta NFC al teléfono para grabar...",
            if (pwd.isNotEmpty()) "Se protegerá con contraseña." else "Sin contraseña: la etiqueta quedará sin proteger.")

    private fun showWriting() =
        render(State.WRITING, COLOR_BLUE, "Grabando…", "No retire la etiqueta.", showProgress = true)

    private fun showSuccess(protected: Boolean) {
        render(State.SUCCESS, COLOR_GREEN,
            if (protected) "✔ ¡Etiqueta grabada y protegida con éxito!" else "✔ ¡Etiqueta grabada con éxito!",
            "Volviendo a AppSheet…")
        ui.postDelayed(autoClose, AUTO_CLOSE_MS)
    }

    private fun showError(title: String, detail: String) =
        render(State.ERROR, COLOR_RED, "✖ $title", detail)

    private fun showNfcDisabled() =
        render(State.NFC_OFF, COLOR_ORANGE, "NFC desactivado",
            "Active el NFC del teléfono y vuelva a esta pantalla.", showSettings = true)

    private fun showNoNfc() =
        render(State.NO_NFC, COLOR_RED, "Este teléfono no tiene NFC",
            "No es posible grabar etiquetas en este equipo.")

    private fun render(
        newState: State,
        color: Int,
        title: String,
        detail: String,
        showProgress: Boolean = false,
        showSettings: Boolean = false
    ) {
        state = newState
        statusBox.background = GradientDrawable().apply {
            cornerRadius = 20f * resources.displayMetrics.density
            setColor(color)
        }
        tvStatus.text = title
        tvDetail.text = detail
        progress.visibility = if (showProgress) View.VISIBLE else View.GONE
        btnNfcSettings.visibility = if (showSettings) View.VISIBLE else View.GONE
    }

    private fun closeAndReturn() {
        ui.removeCallbacks(autoClose)
        // La app vive en su propia tarea: al cerrarla Android regresa a AppSheet
        finishAndRemoveTask()
    }

    companion object {
        private const val AUTO_CLOSE_MS = 2000L
        private const val MAX_DOC_CHARS = 500
        private const val MAX_PWD_CHARS = 64
        private val COLOR_BLUE = Color.parseColor("#1E4E8C")
        private val COLOR_GREEN = Color.parseColor("#1B8A4B")
        private val COLOR_RED = Color.parseColor("#B3261E")
        private val COLOR_ORANGE = Color.parseColor("#F26B21")
        private val COLOR_GREY = Color.parseColor("#3A4A63")
    }
}

// ==================================================================== Escritura NFC

private class NfcWriteException(message: String) : Exception(message)

/**
 * Mapa de memoria NTAG21x (NXP).
 * CFG0 (byte 3 = AUTH0), CFG1 (byte 0 = ACCESS), PWD y PACK son páginas consecutivas.
 */
private enum class NtagModel(
    val label: String,
    val cfg0Page: Int,
    val userBytes: Int,
    val ccSize: Byte
) {
    NTAG213("NTAG213", 0x29, 144, 0x12),
    NTAG215("NTAG215", 0x83, 504, 0x3E),
    NTAG216("NTAG216", 0xE3, 888, 0x6D);

    val cfg1Page get() = cfg0Page + 1
    val pwdPage get() = cfg0Page + 2
    val packPage get() = cfg0Page + 3
}

private object TagWriter {

    private const val CMD_GET_VERSION: Byte = 0x60
    private const val CMD_READ: Byte = 0x30
    private val CMD_WRITE: Byte = 0xA2.toByte()
    private const val CMD_PWD_AUTH: Byte = 0x1B

    private const val FIRST_USER_PAGE = 4
    /** Proteger desde la página 4: toda la memoria de usuario y las páginas de configuración. */
    private const val AUTH0_FROM_PAGE: Byte = 0x04
    private const val TIMEOUT_MS = 1500
    private const val AUTH_LIMIT = 0x07

    private class Keys(val pwd: ByteArray, val pack: ByteArray)

    fun write(tag: Tag, doc: String, pwd: String) {
        val message = NdefMessage(arrayOf(NdefRecord.createTextRecord("es", doc)))

        val nfcA = NfcA.get(tag)
        if (nfcA != null) {
            try {
                nfcA.connect()
                nfcA.timeout = TIMEOUT_MS
                val model = detectNtag(nfcA)
                if (model != null) {
                    writeNtag(nfcA, model, message, pwd)
                    return
                }
            } finally {
                closeQuietly(nfcA)
            }
        }

        // No es NTAG21x
        if (pwd.isNotEmpty()) {
            throw NfcWriteException(
                "La etiqueta no es NTAG213/215/216 y no admite contraseña. Use una etiqueta NTAG21x."
            )
        }
        writeGenericNdef(tag, message)
    }

    // ------------------------------------------------------------ NTAG21x (comandos crudos)

    private fun detectNtag(nfcA: NfcA): NtagModel? {
        val v = try {
            nfcA.transceive(byteArrayOf(CMD_GET_VERSION))
        } catch (e: TagLostException) {
            throw e
        } catch (e: IOException) {
            return null
        }
        // v[1] = 0x04 (NXP), v[2] = 0x04 (NTAG), v[6] = tamaño de memoria
        if (v.size < 8 || v[1] != 0x04.toByte() || v[2] != 0x04.toByte()) return null
        return when (v[6].toInt() and 0xFF) {
            0x0F -> NtagModel.NTAG213
            0x11 -> NtagModel.NTAG215
            0x13 -> NtagModel.NTAG216
            else -> null
        }
    }

    private fun writeNtag(nfcA: NfcA, model: NtagModel, message: NdefMessage, pwd: String) {
        val keys = if (pwd.isNotEmpty()) deriveKeys(pwd) else null

        // 1. ¿La etiqueta ya está protegida?
        var cfg = tryRead(nfcA, model.cfg0Page)
        if (cfg == null) reconnect(nfcA) // un NAK deja el chip en IDLE
        val auth0 = cfg?.let { it[3].toInt() and 0xFF }
        val alreadyProtected = cfg == null || auth0!! <= model.packPage

        if (alreadyProtected) {
            if (keys == null) {
                throw NfcWriteException("Esta etiqueta ya está protegida con contraseña y el enlace no trae 'pwd'.")
            }
            authenticate(nfcA, keys)
            if (cfg == null) cfg = read(nfcA, model.cfg0Page)
        }
        val cfgBytes = cfg!!

        // 2. Capability Container (página 3)
        val cc = read(nfcA, 3)
        when {
            cc[0] == 0xE1.toByte() -> {
                if ((cc[3].toInt() and 0x0F) != 0) {
                    throw NfcWriteException("La etiqueta está bloqueada permanentemente (solo lectura).")
                }
            }
            cc[0] == 0.toByte() && cc[1] == 0.toByte() && cc[2] == 0.toByte() && cc[3] == 0.toByte() ->
                writePage(nfcA, 3, byteArrayOf(0xE1.toByte(), 0x10, model.ccSize, 0x00))
            else -> throw NfcWriteException("La etiqueta no tiene formato NDEF válido.")
        }

        // 3. Mensaje NDEF (TLV) desde la página 4
        val tlv = buildTlv(message.toByteArray())
        if (tlv.size > model.userBytes) {
            throw NfcWriteException(
                "El documento es demasiado largo para ${model.label} (${tlv.size} de ${model.userBytes} bytes)."
            )
        }
        for (i in tlv.indices step 4) {
            writePage(nfcA, FIRST_USER_PAGE + i / 4, tlv.copyOfRange(i, i + 4))
        }

        // 4. Protección por contraseña (AUTH0 se escribe al final)
        if (keys != null) {
            writePage(nfcA, model.pwdPage, keys.pwd)
            writePage(nfcA, model.packPage, byteArrayOf(keys.pack[0], keys.pack[1], 0x00, 0x00))

            val cfg1 = cfgBytes.copyOfRange(4, 8)
            // PROT = 0 → lectura libre, escritura con clave; AUTHLIM = 7 → tras 7 intentos
            // fallidos consecutivos la etiqueta bloquea la autenticación (anti fuerza bruta)
            cfg1[0] = ((cfg1[0].toInt() and 0x78) or AUTH_LIMIT).toByte()
            writePage(nfcA, model.cfg1Page, cfg1)

            val cfg0 = cfgBytes.copyOfRange(0, 4)
            cfg0[3] = AUTH0_FROM_PAGE
            writePage(nfcA, model.cfg0Page, cfg0)
        }

        // 5. Verificación
        val readBack = ByteArrayOutputStream()
        var page = FIRST_USER_PAGE
        while (readBack.size() < tlv.size) {
            readBack.write(read(nfcA, page)) // cada lectura devuelve 4 páginas (16 bytes)
            page += 4
        }
        if (!readBack.toByteArray().copyOfRange(0, tlv.size).contentEquals(tlv)) {
            throw NfcWriteException("La verificación falló: el contenido leído no coincide.")
        }
    }

    private fun authenticate(nfcA: NfcA, keys: Keys) {
        val r = try {
            nfcA.transceive(byteArrayOf(CMD_PWD_AUTH, keys.pwd[0], keys.pwd[1], keys.pwd[2], keys.pwd[3]))
        } catch (e: TagLostException) {
            throw e
        } catch (e: IOException) {
            null
        }
        if (r == null || r.size != 2) {
            throw NfcWriteException("Contraseña incorrecta: esta etiqueta fue protegida con otra llave.")
        }
    }

    private fun read(nfcA: NfcA, page: Int): ByteArray {
        val r = try {
            nfcA.transceive(byteArrayOf(CMD_READ, page.toByte()))
        } catch (e: TagLostException) {
            throw e
        } catch (e: IOException) {
            throw NfcWriteException("La etiqueta rechazó la lectura de la página $page.")
        }
        if (r.size < 16) throw NfcWriteException("La etiqueta rechazó la lectura de la página $page.")
        return r
    }

    private fun tryRead(nfcA: NfcA, page: Int): ByteArray? = try {
        read(nfcA, page)
    } catch (e: NfcWriteException) {
        null
    }

    private fun writePage(nfcA: NfcA, page: Int, data: ByteArray) {
        val r = try {
            nfcA.transceive(byteArrayOf(CMD_WRITE, page.toByte(), data[0], data[1], data[2], data[3]))
        } catch (e: TagLostException) {
            throw e
        } catch (e: IOException) {
            throw NfcWriteException("La etiqueta rechazó la escritura en la página $page (¿bloqueada?).")
        }
        // ACK = 0xA; cualquier otro nibble es NAK
        if (r.size == 1 && (r[0].toInt() and 0x0F) != 0x0A) {
            throw NfcWriteException("La etiqueta rechazó la escritura en la página $page (¿bloqueada?).")
        }
    }

    private fun reconnect(nfcA: NfcA) {
        closeQuietly(nfcA)
        nfcA.connect()
        nfcA.timeout = TIMEOUT_MS
    }

    /** TLV NDEF: 0x03 + longitud + mensaje + 0xFE, rellenado a múltiplo de 4 bytes. */
    private fun buildTlv(ndef: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x03)
        if (ndef.size < 0xFF) {
            out.write(ndef.size)
        } else {
            out.write(0xFF)
            out.write((ndef.size shr 8) and 0xFF)
            out.write(ndef.size and 0xFF)
        }
        out.write(ndef, 0, ndef.size)
        out.write(0xFE)
        while (out.size() % 4 != 0) out.write(0x00)
        return out.toByteArray()
    }

    /**
     * Contraseña NTAG = 4 bytes.
     * - Si [llave] son 8 caracteres hexadecimales (ej. "A1B2C3D4") se usan tal cual,
     *   compatible con apps como NFC Tools.
     * - Cualquier otro texto se convierte con SHA-256 (primeros 4 bytes).
     * PACK = bytes 4-5 del SHA-256 de la llave.
     */
    private fun deriveKeys(pwd: String): Keys {
        val hash = MessageDigest.getInstance("SHA-256").digest(pwd.toByteArray(Charsets.UTF_8))
        val pwdBytes = if (Regex("^[0-9A-Fa-f]{8}$").matches(pwd)) {
            pwd.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } else {
            hash.copyOfRange(0, 4)
        }
        return Keys(pwdBytes, hash.copyOfRange(4, 6))
    }

    // ------------------------------------------------------------ Otras etiquetas (sin contraseña)

    private fun writeGenericNdef(tag: Tag, message: NdefMessage) {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            ndef.use {
                it.connect()
                if (!it.isWritable) throw NfcWriteException("La etiqueta es de solo lectura.")
                val size = message.toByteArray().size
                if (size > it.maxSize) {
                    throw NfcWriteException("El documento no cabe en la etiqueta ($size de ${it.maxSize} bytes).")
                }
                it.writeNdefMessage(message)
            }
            return
        }
        val formatable = NdefFormatable.get(tag)
            ?: throw NfcWriteException("Tipo de etiqueta no compatible con NDEF.")
        formatable.use {
            it.connect()
            it.format(message)
        }
    }

    private fun closeQuietly(nfcA: NfcA) {
        try {
            nfcA.close()
        } catch (_: IOException) {
        }
    }
}

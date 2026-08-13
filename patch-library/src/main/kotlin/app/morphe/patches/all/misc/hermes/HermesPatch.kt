package app.morphe.patches.all.misc.hermes

import app.morphe.patcher.patch.rawResourcePatch
import app.morphe.patches.all.misc.hex.Replacement
import app.morphe.util.byteArrayOf
import app.morphe.util.toInt
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.security.MessageDigest

const val HERMES_BUNDLE_PATH = "assets/index.android.bundle"
val HERMES_MAGIC = byteArrayOf("C6 1F BC 03 C1 03 19 1F")

@Suppress("unused")
fun hermesPatch(supplier: () -> Set<Pair<String, String>>) =
    rawResourcePatch {
        execute {
            val file = get(HERMES_BUNDLE_PATH, true)
            if (!file.exists())
                throw FileNotFoundException("Hermes bytecode bundle not found at: $HERMES_BUNDLE_PATH")

            RandomAccessFile(file, "rw").use { raf ->
                // Check Hermes magic number in header
                val magicBuffer = ByteArray(8)
                raf.readFully(magicBuffer)
                if (!magicBuffer.contentEquals(HERMES_MAGIC))
                    throw Exception("Invalid Hermes file")

                // Get Hermes bytecode version from header
                val versionBuffer = ByteArray(4)
                raf.readFully(versionBuffer)
                val version = versionBuffer.toInt(true)

                // Do byte replacements safely directly on disk
                supplier()
                    .map { Replacement(byteArrayOf(it.first), byteArrayOf(it.second), HERMES_BUNDLE_PATH) }
                    .forEach { it.replacePattern(raf) }

                // Recalculate SHA-1 footer hash via streaming (prevents OOM on huge bundles)
                if (version > 74) {
                    val md = MessageDigest.getInstance("SHA-1")
                    val buffer = ByteArray(65536) // 64KB chunks
                    val hashContentLength = raf.length() - 20
                    var bytesReadTotal = 0L

                    raf.seek(0)
                    while (bytesReadTotal < hashContentLength) {
                        val bytesToRead = minOf(
                            buffer.size.toLong(),
                            hashContentLength - bytesReadTotal
                        ).toInt()

                        val read = raf.read(buffer, 0, bytesToRead)
                        if (read == -1) break

                        md.update(buffer, 0, read)
                        bytesReadTotal += read
                    }

                    // Write the new SHA-1 hash to the last 20 bytes
                    val hash = md.digest()
                    raf.seek(hashContentLength)
                    raf.write(hash)
                }
            }
        }
    }
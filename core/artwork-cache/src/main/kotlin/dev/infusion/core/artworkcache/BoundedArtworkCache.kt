package dev.infusion.core.artworkcache

import dev.infusion.core.domain.catalog.CatalogImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class ArtworkCacheKey(
    val partition: String,
    val nativeItemId: String,
    val kind: String,
    val rendition: String = "",
) {
    private val tokenSeed: String
        get() = if (rendition.isEmpty()) {
            "$partition\u0000$nativeItemId\u0000$kind"
        } else {
            "$partition\u0000$nativeItemId\u0000$kind\u0000$rendition"
        }
    override fun toString(): String = tokenSeed

    val diskId: String get() = sha256Hex(tokenSeed)
}

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(StandardCharsets.UTF_8))
    val sb = StringBuilder(digest.size * 2)
    for (b in digest) sb.append("%02x".format(b.toInt() and 0xff))
    return sb.toString()
}

class BoundedArtworkCache(
    val memoryQuotaBytes: Long = DEFAULT_MEMORY_QUOTA_BYTES,
    val diskQuotaBytes: Long = DEFAULT_DISK_QUOTA_BYTES,
    private val diskRoot: File? = null,
    private val partitionDiskQuotaBytes: Long? = null,

    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    init {
        require(memoryQuotaBytes > 0) { "memoryQuotaBytes must be positive" }
        require(diskQuotaBytes >= 0) { "diskQuotaBytes must be non-negative" }
        require(partitionDiskQuotaBytes == null || partitionDiskQuotaBytes > 0) {
            "partitionDiskQuotaBytes must be positive when set"
        }
    }

    private val lock = Any()

    private val mem = LinkedHashMap<ArtworkCacheKey, MemEntry>(64, 0.75f, true)
    private var memBytes = 0L

    private class MemEntry(val image: CatalogImage, val size: Long)

    private class DiskEntry(

        val partition: String?,
        val diskId: String,
        val img: File,
        val meta: File,
        var size: Long,
    )

    private val diskEntries = LinkedHashMap<String, DiskEntry>(128, 0.75f, true)

    private val partitionEntries = LinkedHashMap<String, LinkedHashMap<String, DiskEntry>>()
    private val partitionDiskBytes = LinkedHashMap<String, Long>()
    private var diskBytes = 0L

    init {

        diskRoot?.let(::scanDisk)
    }

    fun put(key: ArtworkCacheKey, image: CatalogImage) {
        if (image.bytes.isEmpty()) return
        memPut(key, image)
        if (diskRoot != null) diskPut(key, image)
    }

    fun get(key: ArtworkCacheKey): CatalogImage? {
        synchronized(lock) {
            mem[key]?.let { return it.image }
        }
        if (diskRoot == null) return null
        return diskGet(key)
    }

    suspend fun getOrLoad(
        key: ArtworkCacheKey,
        loader: suspend (ArtworkCacheKey) -> CatalogImage?,
    ): CatalogImage? {
        get(key)?.let { return it }
        val fresh = loader(key) ?: return null
        put(key, fresh)
        return fresh
    }

    fun purgePartition(partition: String) {
        synchronized(lock) {
            val keys = mem.keys.filter { it.partition == partition }
            for (k in keys) {
                mem.remove(k)?.let { memBytes -= it.size }
            }
            if (diskRoot == null) return
            for (entry in partitionEntries[partition]?.values?.toList().orEmpty()) {
                deleteEntry(entry)
            }
            partitionEntries.remove(partition)
            partitionDiskBytes.remove(partition)
            partitionDir(partition).delete()

            val legacyName = legacyDirectoryName(partition)
            if (isUnambiguousLegacyName(legacyName)) File(diskRoot, legacyName).deleteRecursively()
        }
    }

    fun clear() {
        synchronized(lock) {
            mem.clear()
            memBytes = 0
            diskEntries.clear()
            partitionEntries.clear()
            partitionDiskBytes.clear()
            diskBytes = 0
            diskRoot?.listFiles()?.forEach { it.deleteRecursively() }
        }
    }

    val memoryEntryCount: Int get() = synchronized(lock) { mem.size }
    val memoryBytesUsed: Long get() = synchronized(lock) { memBytes }
    val diskBytesUsed: Long get() = synchronized(lock) { diskBytes }

    fun diskEntryCount(partition: String? = null): Int = synchronized(lock) {
        if (partition == null) diskEntries.size
        else partitionEntries[partition]?.size ?: 0
    }

    fun diskBytesUsed(partition: String? = null): Long = synchronized(lock) {
        if (partition == null) diskBytes
        else partitionDiskBytes[partition] ?: 0L
    }

    private fun memPut(key: ArtworkCacheKey, image: CatalogImage) {
        val size = image.bytes.size.toLong()
        synchronized(lock) {
            mem.remove(key)?.let { memBytes -= it.size }
            mem[key] = MemEntry(image, size)
            memBytes += size

            while (memBytes > memoryQuotaBytes && mem.size > 1) {
                val itr = mem.entries.iterator()
                val victim = itr.next()
                itr.remove()
                memBytes -= victim.value.size
            }
        }
    }

    private class FoundFile(
        val owner: String?,
        val diskId: String,
        val img: File,
        val size: Long,
        val mtime: Long,
    )

    private fun scanDisk(root: File) {
        val found = ArrayList<FoundFile>()
        root.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val owner = ownerOfDirectory(dir.name)
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory) return@forEach
                when {

                    f.name.endsWith(TMP_SUFFIX) -> f.delete()
                    f.name.endsWith(IMG_SUFFIX) -> {
                        val diskId = f.name.removeSuffix(IMG_SUFFIX)
                        val size = f.length()
                        if (diskId.isEmpty() || size <= 0L) {

                            f.delete()
                            File(dir, diskId + META_SUFFIX).delete()
                        } else {
                            found += FoundFile(owner, diskId, f, size, f.lastModified())
                        }
                    }
                    f.name.endsWith(META_SUFFIX) -> {
                        val imgName = f.name.removeSuffix(META_SUFFIX) + IMG_SUFFIX
                        if (!File(dir, imgName).isFile) f.delete()
                    }
                    else -> Unit
                }
            }
        }

        found.sortWith(compareBy({ it.mtime }, { it.img.absolutePath }))
        for (f in found) register(f.owner, f.diskId, f.img, metaFor(f.img), f.size)
        enforceRestoredQuotas()
    }

    private fun enforceRestoredQuotas() {
        for (partition in partitionEntries.keys.toList()) enforcePartitionQuota(partition)
        enforceGlobalQuota()
    }

    private fun partitionDir(partition: String): File =
        File(diskRoot!!, partitionDirectoryName(partition))

    private fun bytesFile(key: ArtworkCacheKey): File =
        File(partitionDir(key.partition), key.diskId + IMG_SUFFIX)

    private fun legacyBytesFile(key: ArtworkCacheKey): File =
        File(File(diskRoot!!, legacyDirectoryName(key.partition)), key.diskId + IMG_SUFFIX)

    private fun metaFor(img: File): File =
        File(img.parentFile, img.name.removeSuffix(IMG_SUFFIX) + META_SUFFIX)

    private fun partitionDirectoryName(partition: String): String {
        val sb = StringBuilder(PARTITION_DIR_PREFIX)
        for (b in partition.toByteArray(StandardCharsets.UTF_8)) {
            val v = b.toInt() and 0xff
            val c = v.toChar()
            if (isUnreserved(c)) {
                sb.append(c)
            } else {
                sb.append('%').append(HEX[v shr 4]).append(HEX[v and 0x0f])
            }
        }
        return sb.toString()
    }

    private fun decodePartitionDirectoryName(name: String): String? {
        if (!name.startsWith(PARTITION_DIR_PREFIX)) return null
        val out = ByteArrayOutputStream(name.length)
        var i = PARTITION_DIR_PREFIX.length
        while (i < name.length) {
            val c = name[i]
            if (c == '%') {
                if (i + 2 >= name.length) return null
                val hi = hexValue(name[i + 1])
                val lo = hexValue(name[i + 2])
                if (hi < 0 || lo < 0) return null
                out.write((hi shl 4) or lo)
                i += 3
            } else {
                if (!isUnreserved(c)) return null
                out.write(c.code)
                i++
            }
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(out.toByteArray()))
                .toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }

    private fun isUnambiguousLegacyName(name: String): Boolean =
        name.isNotEmpty() && name.all { it.isLetterOrDigit() || it == '.' || it == '-' }

    private fun legacyDirectoryName(partition: String): String =
        partition.map { c ->
            when {
                c.isLetterOrDigit() || c == '.' || c == '-' || c == '_' -> c
                else -> '_'
            }
        }.joinToString("").ifBlank { "p" }

    private fun ownerOfDirectory(name: String): String? =
        decodePartitionDirectoryName(name) ?: name.takeIf { isUnambiguousLegacyName(it) }

    private fun register(
        partition: String?,
        diskId: String,
        img: File,
        meta: File,
        size: Long,
    ): DiskEntry {
        val entry = DiskEntry(partition, diskId, img, meta, size)
        val path = img.absolutePath
        diskEntries[path] = entry
        if (partition != null) {
            partitionEntries.getOrPut(partition) { LinkedHashMap(64, 0.75f, true) }[path] = entry
            partitionDiskBytes[partition] = (partitionDiskBytes[partition] ?: 0L) + size
        }
        diskBytes += size
        return entry
    }

    private fun unregister(entry: DiskEntry) {
        val path = entry.img.absolutePath
        diskEntries.remove(path)
        entry.partition?.let { partition ->
            partitionEntries[partition]?.let { entries ->
                entries.remove(path)
                if (entries.isEmpty()) partitionEntries.remove(partition)
            }
            partitionDiskBytes[partition] = (partitionDiskBytes[partition] ?: 0L) - entry.size
            if ((partitionDiskBytes[partition] ?: 0L) <= 0L) partitionDiskBytes.remove(partition)
        }
        diskBytes -= entry.size
    }

    private fun deleteEntry(entry: DiskEntry): Boolean {
        if (entry.img.exists() && !entry.img.delete()) return false
        entry.meta.delete()
        unregister(entry)
        return true
    }

    private class DiskRead(val entry: DiskEntry, val image: CatalogImage)

    private fun readEntry(key: ArtworkCacheKey, file: File): DiskRead? {
        val path = file.absolutePath
        val entry = diskEntries[path] ?: run {
            if (!file.isFile) return null
            register(key.partition, key.diskId, file, metaFor(file), file.length())
        }
        if (!entry.img.isFile) {

            unregister(entry)
            return null
        }
        val bytes = try {
            entry.img.readBytes()
        } catch (e: Exception) {
            return null
        }
        if (bytes.isEmpty()) {
            deleteEntry(entry)
            return null
        }

        entry.img.setLastModified(nowMs())
        diskEntries[path] = entry
        entry.partition?.let { partition -> partitionEntries[partition]?.put(path, entry) }
        val image = CatalogImage(contentType = metaType(entry.meta), bytes = bytes)
        memPut(key, image)
        return DiskRead(entry, image)
    }

    private fun diskGet(key: ArtworkCacheKey): CatalogImage? {
        synchronized(lock) {
            val primary = bytesFile(key)
            readEntry(key, primary)?.let { return it.image }
            val legacy = legacyBytesFile(key)
            if (legacy.absolutePath == primary.absolutePath) return null
            val read = readEntry(key, legacy) ?: return null

            adoptLegacyEntry(key, read.entry)
            return read.image
        }
    }

    private fun diskPut(key: ArtworkCacheKey, image: CatalogImage) {
        synchronized(lock) {
            val dir = partitionDir(key.partition)
            if (!dir.isDirectory && !dir.mkdirs()) return
            val file = File(dir, key.diskId + IMG_SUFFIX)
            val meta = File(dir, key.diskId + META_SUFFIX)
            val legacy = legacyBytesFile(key)
            if (legacy.absolutePath != file.absolutePath) {

                diskEntries[legacy.absolutePath]?.let { deleteEntry(it) }
            }
            val existing = diskEntries[file.absolutePath]
                ?: if (file.isFile) register(key.partition, key.diskId, file, meta, file.length()) else null
            if (!publish(file, image.bytes)) return
            publish(meta, image.contentType.orEmpty().toByteArray(StandardCharsets.UTF_8))
            file.setLastModified(nowMs())
            val newSize = image.bytes.size.toLong()
            if (existing != null) {
                val delta = newSize - existing.size
                existing.size = newSize
                diskBytes += delta
                existing.partition?.let { partition ->
                    partitionDiskBytes[partition] = (partitionDiskBytes[partition] ?: 0L) + delta
                }
                diskEntries[file.absolutePath] = existing
                existing.partition?.let { partition ->
                    partitionEntries[partition]?.put(file.absolutePath, existing)
                }
            } else {
                register(key.partition, key.diskId, file, meta, newSize)
            }
            enforceDiskQuota(key.partition)
        }
    }

    private fun publish(target: File, bytes: ByteArray): Boolean = try {
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return false
            }
        }
        true
    } catch (e: Exception) {
        false
    }

    private fun adoptLegacyEntry(key: ArtworkCacheKey, entry: DiskEntry) {
        val dir = partitionDir(key.partition)
        val target = File(dir, key.diskId + IMG_SUFFIX)
        if (target.exists()) return
        if (!dir.isDirectory && !dir.mkdirs()) return
        val size = entry.size
        if (!entry.img.renameTo(target)) return
        val targetMeta = File(dir, key.diskId + META_SUFFIX)
        if (!entry.meta.renameTo(targetMeta) && entry.meta.isFile) {
            try {
                targetMeta.writeBytes(entry.meta.readBytes())
                entry.meta.delete()
            } catch (e: Exception) {

            }
        }
        unregister(entry)
        register(key.partition, key.diskId, target, targetMeta, size)
        target.setLastModified(nowMs())
    }

    private fun metaType(meta: File): String? {
        if (!meta.isFile) return null
        return try {
            meta.readText().ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun enforceDiskQuota(insertedPartition: String) {
        enforcePartitionQuota(insertedPartition)
        enforceGlobalQuota()
    }

    private fun enforcePartitionQuota(partition: String) {
        val cap = partitionDiskQuotaBytes ?: return
        if ((partitionDiskBytes[partition] ?: 0L) <= cap) return
        val refused = HashSet<String>()
        while ((partitionDiskBytes[partition] ?: 0L) > cap) {
            val victim = partitionEntries[partition]?.values
                ?.firstOrNull { it.img.absolutePath !in refused }
                ?: return
            if (!deleteEntry(victim)) refused += victim.img.absolutePath
        }
    }

    private fun enforceGlobalQuota() {
        if (diskBytes <= diskQuotaBytes) return
        val refused = HashSet<String>()
        while (diskBytes > diskQuotaBytes) {
            val victim = diskEntries.values.firstOrNull { it.img.absolutePath !in refused } ?: return
            if (!deleteEntry(victim)) refused += victim.img.absolutePath
        }
    }

    private companion object {
        const val IMG_SUFFIX = ".img"
        const val META_SUFFIX = ".meta"
        const val TMP_SUFFIX = ".tmp"
        const val PARTITION_DIR_PREFIX = "~"
        const val HEX = "0123456789abcdef"
        const val DEFAULT_MEMORY_QUOTA_BYTES = 24L * 1024 * 1024
        const val DEFAULT_DISK_QUOTA_BYTES = 96L * 1024 * 1024

        fun isUnreserved(c: Char): Boolean =
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-' || c == '_'

        fun hexValue(c: Char): Int = when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            else -> -1
        }
    }
}

package com.bownee.lenswave.gallery

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import com.bownee.lenswave.storage.AtomicFileStore
import com.bownee.lenswave.storage.SecureFileStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CombinedPhotoCache @Inject constructor(
    @ApplicationContext context: Context,
    private val secureFiles: SecureFileStore,
) : CombinedMatchStore {
    private val root = File(context.filesDir, "combined-photo-cache").apply { mkdirs() }

    override fun read(userId: String): CombinedMatchSnapshot {
        val file = snapshotFile(userId)
        val snapshot = if (!file.isFile) CombinedMatchSnapshot() else runCatching {
            val rootValue = JSONObject(secureFiles.readText(scope(userId), file))
            val values = rootValue.optJSONArray("records") ?: JSONArray()
            val records = buildList {
                for (index in 0 until values.length()) {
                    val value = values.getJSONObject(index)
                    val nodeValues = value.optJSONArray("protonNodeUids") ?: JSONArray()
                    add(
                        DevicePhotoMatchRecord(
                            stableId = value.getString("stableId"),
                            displayName = value.optString("displayName"),
                            sizeBytes = value.optLong("sizeBytes"),
                            modifiedAtEpochMillis = value.optLong("modifiedAt"),
                            checkedTimelineFingerprint = value.optString("checkedTimelineFingerprint")
                                .ifBlank { rootValue.optString("timelineFingerprint") },
                            checkedAtEpochMillis = value.optLong("checkedAt"),
                            matchStrategyVersion = value.optInt("matchStrategyVersion"),
                            sha1Hex = value.optString("sha1").takeIf(String::isNotBlank),
                            protonNodeUids = buildList {
                                for (nodeIndex in 0 until nodeValues.length()) {
                                    add(nodeValues.getString(nodeIndex))
                                }
                            },
                        )
                    )
                }
            }
            CombinedMatchSnapshot(
                timelineFingerprint = rootValue.optString("timelineFingerprint"),
                records = records,
            )
        }.getOrElse {
            file.delete()
            CombinedMatchSnapshot()
        }
        val merged = snapshot.records.associateByTo(linkedMapOf(), DevicePhotoMatchRecord::stableId)
        var fingerprint = snapshot.timelineFingerprint
        journalFile(userId).takeIf(File::isFile)?.forEachLine { line ->
            runCatching {
                val payload = Base64.decode(line, Base64.NO_WRAP)
                JSONObject(secureFiles.decrypt(scope(userId), payload).toString(Charsets.UTF_8))
            }.getOrNull()?.let { value ->
                val recordFingerprint = value.optString("timelineFingerprint")
                if (recordFingerprint.isNotBlank()) fingerprint = recordFingerprint
                parseRecord(value)?.let { record -> merged[record.stableId] = record }
            }
        }
        return CombinedMatchSnapshot(fingerprint, merged.values.toList())
    }

    override fun write(userId: String, snapshot: CombinedMatchSnapshot) {
        val values = JSONArray()
        snapshot.records.forEach { record ->
            values.put(
                JSONObject()
                    .put("stableId", record.stableId)
                    .put("displayName", record.displayName)
                    .put("sizeBytes", record.sizeBytes)
                    .put("modifiedAt", record.modifiedAtEpochMillis)
                    .put("checkedTimelineFingerprint", record.checkedTimelineFingerprint)
                    .put("checkedAt", record.checkedAtEpochMillis)
                    .put("matchStrategyVersion", record.matchStrategyVersion)
                    .put("sha1", record.sha1Hex ?: "")
                    .put("protonNodeUids", JSONArray(record.protonNodeUids))
            )
        }
        val value = JSONObject()
            .put("timelineFingerprint", snapshot.timelineFingerprint)
            .put("records", values)
        val target = snapshotFile(userId)
        secureFiles.writeText(scope(userId), target, value.toString(), "Could not commit combined photo cache")
        journalFile(userId).delete()
    }

    override fun append(
        userId: String,
        timelineFingerprint: String,
        records: Collection<DevicePhotoMatchRecord>,
    ) {
        if (records.isEmpty()) return
        val target = journalFile(userId)
        target.parentFile?.mkdirs()
        FileOutputStream(target, true).bufferedWriter(Charsets.UTF_8).use { writer ->
            records.forEach { record ->
                val json = recordToJson(record)
                    .put("timelineFingerprint", timelineFingerprint)
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                writer.append(Base64.encodeToString(secureFiles.encrypt(scope(userId), json), Base64.NO_WRAP))
                writer.newLine()
            }
        }
    }

    override fun clear(userId: String) {
        snapshotFile(userId).delete()
        journalFile(userId).delete()
        secureFiles.deleteKey(scope(userId))
    }

    private fun snapshotFile(userId: String): File = File(root, "${safeName(userId)}.json")

    private fun journalFile(userId: String): File = File(root, "${safeName(userId)}.journal")

    private fun recordToJson(record: DevicePhotoMatchRecord): JSONObject = JSONObject()
        .put("stableId", record.stableId)
        .put("displayName", record.displayName)
        .put("sizeBytes", record.sizeBytes)
        .put("modifiedAt", record.modifiedAtEpochMillis)
        .put("checkedTimelineFingerprint", record.checkedTimelineFingerprint)
        .put("checkedAt", record.checkedAtEpochMillis)
        .put("matchStrategyVersion", record.matchStrategyVersion)
        .put("sha1", record.sha1Hex ?: "")
        .put("protonNodeUids", JSONArray(record.protonNodeUids))

    private fun parseRecord(value: JSONObject): DevicePhotoMatchRecord? = runCatching {
        val nodeValues = value.optJSONArray("protonNodeUids") ?: JSONArray()
        DevicePhotoMatchRecord(
            stableId = value.getString("stableId"),
            displayName = value.optString("displayName"),
            sizeBytes = value.optLong("sizeBytes"),
            modifiedAtEpochMillis = value.optLong("modifiedAt"),
            checkedTimelineFingerprint = value.optString("checkedTimelineFingerprint"),
            checkedAtEpochMillis = value.optLong("checkedAt"),
            matchStrategyVersion = value.optInt("matchStrategyVersion"),
            sha1Hex = value.optString("sha1").takeIf(String::isNotBlank),
            protonNodeUids = buildList {
                for (index in 0 until nodeValues.length()) add(nodeValues.getString(index))
            },
        )
    }.getOrNull()

    private fun safeName(value: String): String = AtomicFileStore.safeName(value)

    private fun scope(userId: String): String = "combined-cache:$userId"
}

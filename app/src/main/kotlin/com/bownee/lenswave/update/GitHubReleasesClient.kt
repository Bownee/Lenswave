package com.bownee.lenswave.update

import android.util.JsonReader
import android.util.JsonToken
import com.bownee.lenswave.LenswaveDiagnostics
import com.bownee.lenswave.LenswaveOperation
import kotlinx.coroutines.CancellationException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection

internal sealed interface LatestReleaseResult {
    data class Modified(
        val versionName: String,
        val etag: String?,
    ) : LatestReleaseResult

    data object NotModified : LatestReleaseResult

    data object Unavailable : LatestReleaseResult
}

internal interface LatestReleaseClient {
    fun fetch(etag: String?): LatestReleaseResult
}

@Singleton
internal class GitHubReleasesClient
    @Inject
    constructor() : LatestReleaseClient {
        override fun fetch(etag: String?): LatestReleaseResult {
            var connection: HttpsURLConnection? = null
            return try {
                connection = URL(LenswaveReleases.latestReleaseApiUrl).openConnection() as HttpsURLConnection
                connection.apply {
                    requestMethod = "GET"
                    connectTimeout = TIMEOUT_MILLIS
                    readTimeout = TIMEOUT_MILLIS
                    instanceFollowRedirects = false
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
                    setRequestProperty("User-Agent", "Lenswave-Android")
                    etag?.let { setRequestProperty("If-None-Match", it) }
                }
                when (connection.responseCode) {
                    HttpsURLConnection.HTTP_OK -> {
                        val versionName =
                            GitHubReleaseJson.readVersionName(connection.inputStream)
                                ?: return LatestReleaseResult.Unavailable
                        LatestReleaseResult.Modified(
                            versionName = versionName.removePrefix("v").removePrefix("V"),
                            etag = connection.getHeaderField("ETag"),
                        )
                    }

                    HttpsURLConnection.HTTP_NOT_MODIFIED -> {
                        LatestReleaseResult.NotModified
                    }

                    else -> {
                        LatestReleaseResult.Unavailable
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                LenswaveDiagnostics.reportFailure(LenswaveOperation.APP_UPDATE_CHECK, error)
                LatestReleaseResult.Unavailable
            } finally {
                connection?.disconnect()
            }
        }

        private companion object {
            const val GITHUB_API_VERSION = "2026-03-10"
            const val TIMEOUT_MILLIS = 5_000
        }
    }

internal object GitHubReleaseJson {
    /** The release's tag name, or null when the document has none or it is not a plausible tag (see [ReleaseTagPolicy]). */
    fun readVersionName(input: InputStream): String? =
        JsonReader(
            InputStreamReader(input, Charsets.UTF_8),
        ).use { reader ->
            var versionName: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == "tag_name" && reader.peek() == JsonToken.STRING) {
                    versionName = reader.nextString()
                } else {
                    reader.skipValue()
                }
            }
            reader.endObject()
            ReleaseTagPolicy.accept(versionName)
        }
}

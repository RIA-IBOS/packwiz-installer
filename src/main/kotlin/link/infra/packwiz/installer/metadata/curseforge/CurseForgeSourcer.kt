package link.infra.packwiz.installer.metadata.curseforge

import link.infra.packwiz.installer.AppInfo
import com.google.gson.Gson
import com.google.gson.JsonIOException
import com.google.gson.JsonSyntaxException
import link.infra.packwiz.installer.config.CurseForgeConfig
import link.infra.packwiz.installer.metadata.IndexFile
import link.infra.packwiz.installer.target.ClientHolder
import link.infra.packwiz.installer.target.path.HttpUrlPath
import link.infra.packwiz.installer.target.path.PackwizFilePath
import link.infra.packwiz.installer.ui.data.ExceptionDetails
import link.infra.packwiz.installer.util.Log
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.internal.closeQuietly
import okio.ByteString.Companion.decodeBase64
import java.nio.charset.StandardCharsets
import kotlin.io.path.absolute

private class GetFilesRequest(val fileIds: List<Int>)
private class GetModsRequest(val modIds: List<Int>)

private class GetFilesResponse {
	class CfFile {
		var id = 0
		var modId = 0
		var downloadUrl: String? = null
	}
	val data = mutableListOf<CfFile>()
}

private class GetModsResponse {
	class CfMod {
		var id = 0
		var name = ""
		var links: CfLinks? = null
	}
	class CfLinks {
		var websiteUrl = ""
	}
	val data = mutableListOf<CfMod>()
}

// If you fork/derive from packwiz, I request that you obtain your own API key.
private val APIKey = "JDJhJDEwJHNBWVhqblU1N0EzSmpzcmJYM3JVdk92UWk2NHBLS3BnQ2VpbGc1TUM1UGNKL0RYTmlGWWxh".decodeBase64()!!
	.string(StandardCharsets.UTF_8)

/**
 * Executes an HTTP request with fallback to mirror API.
 * Tries primary API first, then mirror if primary fails with network/server errors.
 *
 * @param endpoint The API endpoint path (e.g., "/mods/files")
 * @param requestBody The JSON request body
 * @param clientHolder The HTTP client holder
 * @param operationName Human-readable operation name for logging
 * @return The successful response
 * @throws Exception if all endpoints fail
 */
private fun executeWithFallback(
	endpoint: String,
	requestBody: okhttp3.RequestBody,
	clientHolder: ClientHolder,
	operationName: String
): Response {
	val apiUrls = CurseForgeConfig.getApiUrls()
	var lastException: Exception? = null

	for ((index, baseUrl) in apiUrls.withIndex()) {
		val isPrimary = index == 0
		val apiType = if (isPrimary) "primary" else "mirror"

		try {
			val url = "$baseUrl$endpoint"
			Log.info("Attempting CurseForge $operationName via $apiType API: $url")

			val req = Request.Builder()
				.url(url)
				.header("Accept", "application/json")
				.header("User-Agent", AppInfo.DISPLAY_NAME)
				.header("X-API-Key", APIKey)
				.post(requestBody)
				.build()

			val res = clientHolder.okHttpClient.newCall(req).execute()

			// Check if response is successful
			if (res.isSuccessful && res.body != null) {
				if (!isPrimary) {
					Log.info("Successfully retrieved $operationName from $apiType API")
				}
				return res
			}

			// For 4xx errors (client errors), don't fallback - it's likely an API issue
			if (res.code in 400..499) {
				res.closeQuietly()
				throw Exception("Failed to resolve CurseForge metadata for $operationName: error code ${res.code}")
			}

			// For 5xx errors or empty body, try fallback
			res.closeQuietly()
			lastException = Exception("$apiType API returned error code ${res.code}")
			Log.warn("$apiType API failed for $operationName: ${res.code}, trying next endpoint...")

		} catch (e: Exception) {
			// If this is a 4xx error exception we just threw, re-throw it immediately
			if (e.message?.contains("error code 4") == true) {
				throw e
			}
			// Network errors, timeouts, etc. - try fallback
			lastException = e
			Log.warn("$apiType API failed for $operationName: ${e.message}, trying next endpoint...")
		}
	}

	// All endpoints failed
	throw Exception("Failed to resolve CurseForge metadata for $operationName: all endpoints failed. Last error: ${lastException?.message}", lastException)
}

@Throws(JsonSyntaxException::class, JsonIOException::class)
fun resolveCfMetadata(mods: List<IndexFile.File>, packFolder: PackwizFilePath, clientHolder: ClientHolder): List<ExceptionDetails> {
	val failures = mutableListOf<ExceptionDetails>()
	val fileIdMap = mutableMapOf<Int, List<IndexFile.File>>()

	for (mod in mods) {
		if (!mod.linkedFile!!.update.contains("curseforge")) {
			failures.add(ExceptionDetails(mod.linkedFile!!.name, Exception("Failed to resolve CurseForge metadata: no CurseForge update section")))
			continue
		}
		val fileId = (mod.linkedFile!!.update["curseforge"] as CurseForgeUpdateData).fileId
		fileIdMap[fileId] = (fileIdMap[fileId] ?: listOf()) + mod
	}

	val reqData = GetFilesRequest(fileIdMap.keys.toList())
	val requestBody = Gson().toJson(reqData, GetFilesRequest::class.java)
		.toRequestBody("application/json".toMediaType())

	val res = try {
		executeWithFallback("/mods/files", requestBody, clientHolder, "file data")
	} catch (e: Exception) {
		failures.add(ExceptionDetails("Other", e))
		return failures
	}

	val resData = Gson().fromJson(res.body!!.charStream(), GetFilesResponse::class.java)
	res.closeQuietly()

	val manualDownloadMods = mutableMapOf<Int, List<Int>>()
	for (file in resData.data) {
		if (!fileIdMap.contains(file.id)) {
			failures.add(ExceptionDetails(file.id.toString(),
				Exception("Failed to find file from result: ID ${file.id}, Project ID ${file.modId}")))
			continue
		}
		if (file.downloadUrl == null) {
			manualDownloadMods[file.modId] = (manualDownloadMods[file.modId] ?: listOf()) + file.id
			continue
		}
		try {
			for (indexFile in fileIdMap[file.id]!!) {
				indexFile.linkedFile!!.resolvedUpdateData["curseforge"] =
					HttpUrlPath(file.downloadUrl!!.toHttpUrl())
			}
		} catch (e: IllegalArgumentException) {
			failures.add(ExceptionDetails(file.id.toString(),
				Exception("Failed to parse URL: ${file.downloadUrl} for ID ${file.id}, Project ID ${file.modId}", e)))
		}
	}

	// Some file types don't show up in the API at all! (e.g. shaderpacks)
	// Add unresolved files to manualDownloadMods
	for ((fileId, indexFiles) in fileIdMap) {
		for (file in indexFiles) {
			if (file.linkedFile != null) {
				if (file.linkedFile!!.resolvedUpdateData["curseforge"] == null) {
					val projectId = (file.linkedFile!!.update["curseforge"] as CurseForgeUpdateData).projectId
					manualDownloadMods[projectId] = (manualDownloadMods[projectId] ?: listOf()) + fileId
				}
			}
		}
	}

	if (manualDownloadMods.isNotEmpty()) {
		val reqModsData = GetModsRequest(manualDownloadMods.keys.toList())
		val requestBodyMods = Gson().toJson(reqModsData, GetModsRequest::class.java)
			.toRequestBody("application/json".toMediaType())

		val resMods = try {
			executeWithFallback("/mods", requestBodyMods, clientHolder, "mod data")
		} catch (e: Exception) {
			failures.add(ExceptionDetails("Other", e))
			return failures
		}

		val resModsData = Gson().fromJson(resMods.body!!.charStream(), GetModsResponse::class.java)
		resMods.closeQuietly()

		for (mod in resModsData.data) {
			if (!manualDownloadMods.contains(mod.id)) {
				failures.add(ExceptionDetails(mod.name,
					Exception("Failed to find project from result: ID ${mod.id}")))
				continue
			}

			for (fileId in manualDownloadMods[mod.id]!!) {
				if (!fileIdMap.contains(fileId)) {
					failures.add(ExceptionDetails(mod.name,
						Exception("Failed to find file from result: file ID $fileId")))
					continue
				}

				for (indexFile in fileIdMap[fileId]!!) {
					var modUrl = "${mod.links?.websiteUrl}/files/${fileId}"
					failures.add(ExceptionDetails(indexFile.name, Exception("This mod is excluded from the CurseForge API and must be downloaded manually.\n" +
						"Please go to ${modUrl} and save this file to ${indexFile.destURI.rebase(packFolder).nioPath.absolute()}"), modUrl))
				}
			}
		}
	}

	return failures
}

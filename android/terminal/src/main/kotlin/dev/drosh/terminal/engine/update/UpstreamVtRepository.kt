package dev.drosh.terminal.engine.update

/**
 * `libghostty-vt.so` from its own upstream release.
 *
 * The engine is somebody else's build; we verify it rather than rebuild it.
 * Release and nightly channels both exist upstream, so both are supported.
 */
class UpstreamVtRepository : UpdateRepository {
    override val id = ComponentId.GHOSTTY_VT

    override fun supports(channel: UpdateChannel): Boolean = true

    override suspend fun latest(channel: UpdateChannel, abi: String): ComponentRelease? {
        val releases = releases() ?: return null
        for (release in releases) {
            if (release.draft) continue
            val tag = release.tag_name
            val matched = when (channel) {
                UpdateChannel.RELEASE -> tag.startsWith("v")
                UpdateChannel.NIGHTLY -> tag.startsWith("nightly-")
            }
            if (!matched) continue
            val asset = release.assets.firstOrNull { it.name == "libghostty-vt-$abi.so" } ?: continue
            val digest = digestFor(release, "libghostty-vt-$abi.so", abi) ?: continue
            return ComponentRelease(
                id = id.key,
                version = tag.removePrefix("v").removePrefix("nightly-"),
                abi = abi,
                sha256 = digest,
                sizeBytes = asset.size,
                url = asset.url,
                publishedAt = release.published_at ?: "",
            )
        }
        return null
    }

    override suspend fun download(release: ComponentRelease): ByteArray? =
        httpGetBytes(release.url)

    private suspend fun releases(): List<GhRelease>? {
        val body = httpGetText("https://api.github.com/repos/ancientcatz/libghostty-android/releases?per_page=20")
            ?: return null
        val list = runCatching { UpdateJson.decodeFromString<List<GhRelease>>(body) }.getOrNull()
            ?: return null
        return list.sortedByDescending { versionKey(it.tag_name) }
    }

    private fun versionKey(tag: String): List<Int> =
        tag.trimStart('v').trimStart('n').trimStart('i').trimStart('g').trimStart('h')
            .trimStart('t').trimStart('l').trimStart('y').trimStart('-')
            .split('.').mapNotNull { it.toIntOrNull() }

    private suspend fun digestFor(release: GhRelease, fileName: String, abi: String): String? {
        val sumsAsset = release.assets.firstOrNull { it.name == "SHA256SUMS" }
        if (sumsAsset != null) {
            val body = httpGetText(sumsAsset.url) ?: return null
            for (line in body.lineSequence()) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2 && parts[1] == fileName) return parts[0].lowercase()
            }
            return null
        }
        val perAbi = release.assets.firstOrNull { it.name == "libghostty-vt-$abi.sha256" }
            ?: return null
        val body = httpGetText(perAbi.url) ?: return null
        return body.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()
    }
}

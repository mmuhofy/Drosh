package dev.drosh.terminal.engine.update

/**
 * `libchrootng.so` from the fork we publish.
 *
 * Only release channel: the fork does not publish nightly builds, and the
 * binary is a static freestanding executable rather than a shared library, so
 * there is nothing to track between releases.
 */
class ChrootNgRepository : UpdateRepository {
    override val id = ComponentId.CHROOT_NG

    override fun supports(channel: UpdateChannel): Boolean =
        channel == UpdateChannel.RELEASE

    override suspend fun latest(channel: UpdateChannel, abi: String): ComponentRelease? {
        if (channel != UpdateChannel.RELEASE) return null
        val releases = releases() ?: return null
        for (release in releases) {
            if (release.draft || release.prerelease) continue
            val tag = release.tag_name
            if (!tag.startsWith("v")) continue
            val asset = release.assets.firstOrNull { it.name == "libchrootng.so" } ?: continue
            val digest = digestFor(release) ?: continue
            return ComponentRelease(
                id = id.key,
                version = tag.removePrefix("v"),
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
        val body = httpGetText("https://api.github.com/repos/mmuhofy/chroot-ng/releases?per_page=20")
            ?: return null
        val list = runCatching { UpdateJson.decodeFromString<List<GhRelease>>(body) }.getOrNull()
            ?: return null
        // The API orders by creation date, not by version. A release created
        // out of order (1.3.0 after 1.6.0) would otherwise be offered first.
        return list.sortedByDescending { versionKey(it.tag_name) }
    }

    private fun versionKey(tag: String): String {
        val cleaned = tag.removePrefix("v")
        return cleaned.split('.').joinToString(".") { part ->
            part.filter { it.isDigit() }.padStart(6, '0')
        }
    }

    private suspend fun digestFor(release: GhRelease): String? {
        val asset = release.assets.firstOrNull { it.name == "libchrootng.so.sha256" }
        if (asset != null) {
            val body = httpGetText(asset.url) ?: return null
            return body.trim().split(Regex("\\s+")).firstOrNull()?.lowercase()
        }
        return null
    }
}

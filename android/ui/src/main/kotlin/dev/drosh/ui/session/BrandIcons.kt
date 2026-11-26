package dev.drosh.ui.session

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.drosh.ui.R

/**
 * The brand drawables, by Simple Icons slug.
 *
 * The pictures are vector drawables generated from Simple Icons (CC0-1.0) by
 * `tools/icons/fetch_icons.py`; this is the one place a slug becomes a
 * resource. A slug with no drawable is not an error — it is a program whose
 * mark is not in the set, and the caller draws its own glyph instead.
 */
object BrandIcons {

    /** The drawable for a slug, or null when the set has no such mark. */
    fun drawable(slug: String?): Int? = when (slug) {
        "archlinux" -> R.drawable.brand_archlinux
        "bun" -> R.drawable.brand_bun
        "claude" -> R.drawable.brand_claude
        "claudecode" -> R.drawable.brand_claudecode
        "debian" -> R.drawable.brand_debian
        "deno" -> R.drawable.brand_deno
        "docker" -> R.drawable.brand_docker
        "fedora" -> R.drawable.brand_fedora
        "git" -> R.drawable.brand_git
        "github" -> R.drawable.brand_github
        "gnubash" -> R.drawable.brand_gnubash
        "go" -> R.drawable.brand_go
        "googlegemini" -> R.drawable.brand_googlegemini
        "helm" -> R.drawable.brand_helm
        "htop" -> R.drawable.brand_htop
        "kubernetes" -> R.drawable.brand_kubernetes
        "lua" -> R.drawable.brand_lua
        "neovim" -> R.drawable.brand_neovim
        "nodedotjs" -> R.drawable.brand_nodedotjs
        "npm" -> R.drawable.brand_npm
        "ollama" -> R.drawable.brand_ollama
        "openjdk" -> R.drawable.brand_openjdk
        "perl" -> R.drawable.brand_perl
        "php" -> R.drawable.brand_php
        "pnpm" -> R.drawable.brand_pnpm
        "podman" -> R.drawable.brand_podman
        "python" -> R.drawable.brand_python
        "ruby" -> R.drawable.brand_ruby
        "rust" -> R.drawable.brand_rust
        "tmux" -> R.drawable.brand_tmux
        "ubuntu" -> R.drawable.brand_ubuntu
        "vim" -> R.drawable.brand_vim
        "yarn" -> R.drawable.brand_yarn
        "zsh" -> R.drawable.brand_zsh
        else -> null
    }
}

/**
 * A program's mark, tinted like the rest of the chrome.
 *
 * Tinted rather than drawn in its brand colour: the cards sit on the app's
 * surface, and six saturated logos on it would fight the terminal previews for
 * attention. One colour keeps the grid readable and the shape does the
 * identifying.
 */
@Composable
internal fun BrandGlyph(
    slug: String?,
    tint: Color,
    modifier: Modifier = Modifier,
    sizeDp: Int = 13,
) {
    val drawable = BrandIcons.drawable(slug) ?: return
    Icon(
        painter = painterResource(drawable),
        contentDescription = null,
        tint = tint,
        modifier = modifier.size(sizeDp.dp),
    )
}

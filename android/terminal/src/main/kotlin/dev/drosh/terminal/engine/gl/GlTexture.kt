package dev.drosh.terminal.engine.gl

import android.opengl.GLES20
import android.opengl.GLES30
import java.nio.ByteBuffer

/**
 * One GL texture object.
 *
 * The texture owns a single handle, its filtering and wrapping, and nothing
 * else: it does not know what its texels mean, nor which pass samples it. The
 * renderer is the one that decides an image is a glyph mask, an emoji or a
 * picture.
 *
 * ## Filtering
 *
 * The atlases are `LINEAR`: a mask drawn at another size — a pinch, a
 * fractional cell step — is interpolated rather than pixel-doubled, and nearest
 * doubled every stroke, which is text that grew bolder as it grew bigger. The
 * kitty pictures are `NEAREST`, because a picture is a picture and nothing in
 * it should be softened. The difference is the [nearest] flag, so the caller
 * keeps the choice.
 *
 * Every method must run on the thread that owns the GL context — [RenderThread]
 * is that thread.
 */
internal class GlTexture {

    /** The texture object's name, or 0 before [create] and after [release]. */
    var handle = 0
        private set

    /**
     * Create the texture and its storage.
     *
     * On an existing handle the storage is re-allocated in place rather than a
     * new name being taken: a kitty picture that changes size keeps its texture
     * name, which is what re-uploading its pixels in [subImage] expects.
     */
    fun create(width: Int, height: Int, internalFormat: Int, nearest: Boolean = false) {
        if (handle == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            handle = ids[0]
        }
        bind()
        val filter = if (nearest) GLES20.GL_NEAREST else GLES20.GL_LINEAR
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            internalFormat,
            width,
            height,
            0,
            formatFor(internalFormat),
            GLES20.GL_UNSIGNED_BYTE,
            null,
        )
    }

    /** Delete the texture, if it was ever created. */
    fun release() {
        if (handle == 0) return
        GLES20.glDeleteTextures(1, intArrayOf(handle), 0)
        handle = 0
    }

    /** Bind the texture to the current texture unit. */
    fun bind() {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, handle)
    }

    /**
     * Write a rectangle of texels into the storage allocated by [create].
     *
     * The bytes go in as they are: an atlas row and a picture row are not
     * padded to four bytes, so the caller sets the unpack alignment to one
     * before uploading a picture.
     */
    fun subImage(
        xOffset: Int,
        yOffset: Int,
        width: Int,
        height: Int,
        format: Int,
        buffer: ByteBuffer,
    ) {
        bind()
        GLES20.glTexSubImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            xOffset,
            yOffset,
            width,
            height,
            format,
            GLES20.GL_UNSIGNED_BYTE,
            buffer,
        )
    }

    /**
     * The pixel format that goes with an internal format.
     *
     * A single-byte texture is `GL_R8` internally and `GL_RED` as upload data;
     * every other format the renderer uses has the same name in both places.
     */
    private fun formatFor(internalFormat: Int): Int = when (internalFormat) {
        GLES30.GL_R8 -> GLES30.GL_RED
        else -> internalFormat
    }
}

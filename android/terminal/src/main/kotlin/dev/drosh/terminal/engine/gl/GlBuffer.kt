package dev.drosh.terminal.engine.gl

import android.opengl.GLES20
import java.nio.ByteBuffer

/**
 * One GL vertex buffer object.
 *
 * The buffer owns a single handle and nothing else: it does not know an
 * attribute layout, a shader or a pass. Those belong to the renderer, which is
 * the only thing that knows what the bytes in the buffer mean.
 *
 * The storage is always `GL_DYNAMIC_DRAW`, because every buffer here is
 * rewritten as the frame changes, not uploaded once and left alone.
 *
 * Every method must run on the thread that owns the GL context — [RenderThread]
 * is that thread.
 */
internal class GlBuffer {

    /** The buffer object's name, or 0 before [create] and after [release]. */
    var handle = 0
        private set

    /** Create the buffer. Called once, with the context current. */
    fun create() {
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        handle = ids[0]
    }

    /** Delete the buffer, if it was ever created. */
    fun release() {
        if (handle == 0) return
        GLES20.glDeleteBuffers(1, intArrayOf(handle), 0)
        handle = 0
    }

    /** Bind the buffer as the array buffer, for the draws that read it. */
    fun bind() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, handle)
    }

    /**
     * Allocate the buffer's storage on the GPU and copy [buffer] into it.
     *
     * Used whenever the buffer the GPU holds is the wrong size, which is the
     * first frame and any frame after the grid changed shape: a sub-update has
     * nowhere to write into a buffer that was never sized.
     */
    fun data(sizeBytes: Int, buffer: ByteBuffer) {
        bind()
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, sizeBytes, buffer, GLES20.GL_DYNAMIC_DRAW)
    }

    /**
     * Write over part of the storage already on the GPU.
     *
     * The rows that did not change are already on the GPU and are not touched:
     * only the bytes from [offset] for [size] bytes go up.
     */
    fun subData(offset: Int, size: Int, buffer: ByteBuffer) {
        bind()
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, offset, size, buffer)
    }
}

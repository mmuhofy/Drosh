package dev.drosh.terminal.engine.render

import android.opengl.GLES20
import dev.drosh.terminal.engine.gl.GlProgram

/**
 * The terminal's global background, drawn as one full-screen triangle.
 *
 * ## Why a triangle and not `glClear`
 *
 * `glClear` is a clear, not a pass: it cannot be reasoned about with the rest
 * of the frame, it has its own state (the clear colour, the scissor, the colour
 * mask), and on a tile-based mobile GPU it can force a resolve before the next
 * draw. Ghostty treats the background as the first render pass. This is the
 * same shape: one program, one uniform, three vertices, and the cell
 * backgrounds blend on top of it.
 *
 * ## Why no vertex buffer
 *
 * The three corners are derived from `gl_VertexID`, so there is nothing to
 * upload, nothing to bind, and nothing that can go out of sync with the
 * surface's size. A full-screen triangle is also strictly cheaper than two
 * triangles: one primitive, one clip, no diagonal seam for the rasteriser to
 * walk twice.
 *
 * The pass owns its program. A driver that cannot build it leaves [available]
 * false and the renderer falls back to `glClear`, which is the safe path.
 *
 * Every method runs on the render thread, with the GL context current.
 */
internal class ScreenBackgroundPass {

    private val program = GlProgram(Shaders.SCREEN_VERTEX, Shaders.SCREEN_FRAGMENT)

    private var colorUniform = -1

    /** True when the program compiled and linked. */
    var available: Boolean = false
        private set

    /** Why the pass is unavailable, or null. */
    val failure: String? get() = program.error

    /** Compile and link the program. */
    fun create() {
        if (!program.create()) {
            available = false
            return
        }
        colorUniform = program.uniform("uColor")
        available = true
    }

    /** Release the program. */
    fun release() {
        program.release()
        available = false
        colorUniform = -1
    }

    /**
     * Paint the whole viewport with [argb].
     *
     * Blending is disabled for the pass: the background has to replace what was
     * there, including the previous frame's pixels when the terminal is
     * transparent. Every later pass blends over the result.
     */
    fun draw(argb: Int) {
        if (!available) return

        GLES20.glDisable(GLES20.GL_BLEND)
        program.use()
        GLES20.glUniform4f(
            colorUniform,
            ((argb ushr 16) and 0xff) / 255f,
            ((argb ushr 8) and 0xff) / 255f,
            (argb and 0xff) / 255f,
            ((argb ushr 24) and 0xff) / 255f,
        )
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
        GLES20.glEnable(GLES20.GL_BLEND)
    }
}

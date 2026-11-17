package dev.drosh.terminal.engine.gl

import android.opengl.GLES20

/**
 * One linked GL program: a vertex and a fragment shader, compiled once.
 *
 * The program owns its handle and the uniform locations it was asked for. The
 * locations are cached because [GLES20.glGetUniformLocation] is a string lookup
 * into the driver, and the renderer asks for the same handful of names on every
 * frame; the cache turns that into a map read.
 *
 * A compile or link failure leaves [handle] at 0 and the reason in [error],
 * which the renderer reads to report why the renderer never came up.
 *
 * Every method must run on the thread that owns the GL context — [RenderThread]
 * is that thread.
 */
internal class GlProgram(
    private val vertexSource: String,
    private val fragmentSource: String,
) {

    /** The program object's name, or 0 before [create] and after [release]. */
    var handle = 0
        private set

    /**
     * Why the last [create] gave up, or null.
     *
     * Kept per program so the renderer can report the newest failure of the
     * four it builds, which is the one worth showing.
     */
    var error: String? = null
        private set

    private val uniforms = HashMap<String, Int>()

    /**
     * Compile and link the program.
     *
     * @return true when the program is ready to [use]; false when a shader did
     * not compile or the link failed, with the reason in [error].
     */
    fun create(): Boolean {
        val vertexShader = compile(GLES20.GL_VERTEX_SHADER, vertexSource)
        if (vertexShader == 0) return false
        val fragmentShader = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource)
        if (fragmentShader == 0) return false

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)

        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            val message = "program link failed: $log"
            error = message
            android.util.Log.e(TAG, message)
            GLES20.glDeleteProgram(program)
            return false
        }
        handle = program
        return true
    }

    /** Delete the program, if it was ever created. */
    fun release() {
        if (handle == 0) return
        GLES20.glDeleteProgram(handle)
        handle = 0
        uniforms.clear()
    }

    /** Make the program current. */
    fun use() {
        GLES20.glUseProgram(handle)
    }

    /** The location of a uniform, looked up once and cached. */
    fun uniform(name: String): Int {
        uniforms[name]?.let { return it }
        val location = GLES20.glGetUniformLocation(handle, name)
        uniforms[name] = location
        return location
    }

    private fun compile(kind: Int, source: String): Int {
        val shader = GLES20.glCreateShader(kind)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            val message = "shader compile failed: $log"
            error = message
            android.util.Log.e(TAG, message)
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private companion object {
        /** The tag the GL failures are logged under, kept from the renderer. */
        const val TAG = "FrameRenderer"
    }
}

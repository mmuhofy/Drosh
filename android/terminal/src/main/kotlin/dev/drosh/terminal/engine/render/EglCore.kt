package dev.drosh.terminal.engine.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface

/**
 * The EGL context, owned by us rather than by `GLSurfaceView`.
 *
 * ## Why not `GLSurfaceView`
 *
 * `GLSurfaceView` owns three things it should not: when a frame is produced
 * (`RENDERMODE_WHEN_DIRTY`), whether the context survives a pause
 * (`preserveEGLContextOnPause`, which is best-effort), and when the surface
 * changes (a callback separate from the draw). Every screen bug of the previous
 * engine came from one of those three:
 *
 * - a keyboard opening recreates the surface, the context is lost, and the
 *   first frame after it is a black rectangle;
 * - a frame that decides not to draw still swaps buffers, so the screen wipes
 *   to the clear colour and comes back;
 * - the resize callback and the draw callback are not ordered, so a frame can
 *   be drawn at one size with a grid from another.
 *
 * Owning the context removes all three. `eglSwapBuffers` is called only when
 * something was actually drawn, so a frame that draws nothing leaves the
 * previous picture alone. The context is not destroyed on pause unless we
 * choose to. And the draw loop is ours, so the order is ours.
 *
 * ## The one rule
 *
 * Everything here must be called on the thread that created it. EGL contexts
 * are not shareable across threads without explicit sharing, and the render
 * loop is the only thread that touches this.
 */
class EglCore {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var surface: EGLSurface = EGL14.EGL_NO_SURFACE

    /** The surface this is currently drawing into, or null. */
    var windowSurface: EGLSurface? = null
        private set

    /** True once a context exists. */
    val isReady: Boolean
        get() = display != EGL14.EGL_NO_DISPLAY && context != EGL14.EGL_NO_CONTEXT

    /**
     * Create the display, choose a config and create the context.
     *
     * Returns false when EGL refused. A device that cannot give a GLES 2
     * context cannot run this terminal at all, and the caller's only sensible
     * response is to say so rather than to draw nothing.
     */
    fun initialize(): Boolean {
        if (isReady) return true

        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return false

        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            display = EGL14.EGL_NO_DISPLAY
            return false
        }

        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            // The terminal draws its own background, so a depth buffer would
            // only cost memory.
            EGL14.EGL_DEPTH_SIZE, 0,
            EGL14.EGL_STENCIL_SIZE, 0,
            // GLES 3, not 2. The shaders are `#version 300 es` and the
            // renderer draws with instanced arrays — a GLES 2 context compiles
            // neither, so every frame would fail and the surface would stay
            // black. Every device that runs this app has GLES 3.
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            // An alpha channel, so a background image behind the terminal can
            // show through: the surface is wiped to transparent when there is
            // one, and the view's own background is the picture.
            //
            // Not every device offers a translucent config with the rest of
            // this list; the config is chosen again without the alpha when
            // there is none, and the terminal then paints its own background
            // as it always did.
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        var chosen = EGL14.eglChooseConfig(
            display, attributes, 0, configs, 0, 1, configCount, 0,
        ) && configCount[0] > 0
        if (!chosen) {
            // The same list without the alpha channel: the terminal then paints
            // its own background, as it always did, and the background image
            // is simply not shown.
            val opaque = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 0,
                EGL14.EGL_STENCIL_SIZE, 0,
                EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                EGL14.EGL_NONE,
            )
            chosen = EGL14.eglChooseConfig(
                display, opaque, 0, configs, 0, 1, configCount, 0,
            ) && configCount[0] > 0
        }
        if (!chosen) {
            release()
            return false
        }
        config = configs[0]

        val contextAttributes = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE,
        )
        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            contextAttributes,
            0,
        )
        if (context == EGL14.EGL_NO_CONTEXT) {
            release()
            return false
        }

        return true
    }

    /**
     * Attach a window surface and make it current.
     *
     * Called when the `SurfaceHolder` reports a surface, and again after a
     * size change. The old surface is destroyed first: two window surfaces on
     * one context is undefined.
     */
    fun attach(surface: Surface): Boolean {
        if (!isReady) return false

        detach()

        val attributes = intArrayOf(EGL14.EGL_NONE)
        windowSurface = EGL14.eglCreateWindowSurface(display, config, surface, attributes, 0)
        if (windowSurface == EGL14.EGL_NO_SURFACE) {
            windowSurface = null
            return false
        }
        this.surface = windowSurface ?: EGL14.EGL_NO_SURFACE

        if (!EGL14.eglMakeCurrent(display, this.surface, this.surface, context)) {
            detach()
            return false
        }
        return true
    }

    /** Drop the window surface. The context survives, which is the point. */
    fun detach() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                display,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT,
            )
        }
        if (surface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, surface)
            surface = EGL14.EGL_NO_SURFACE
        }
        windowSurface = null
    }

    /**
     * Present the frame just drawn.
     *
     * The only place `eglSwapBuffers` is called. A frame that draws nothing
     * does not reach here, which is what stops the screen from wiping to the
     * clear colour and coming back.
     */
    fun swap(): Boolean {
        if (!isReady || surface == EGL14.EGL_NO_SURFACE) return false
        return EGL14.eglSwapBuffers(display, surface)
    }

    /** The surface's size in pixels, or null when there is no surface. */
    fun surfaceSize(): Pair<Int, Int>? {
        if (!isReady || surface == EGL14.EGL_NO_SURFACE) return null
        val width = IntArray(1)
        val height = IntArray(1)
        if (!EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, width, 0) ||
            !EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, height, 0)
        ) {
            return null
        }
        return width[0] to height[0]
    }

    /**
     * Destroy the surface and the context.
     *
     * Called when the view is released for good, not on a pause: a pause keeps
     * the context so the terminal does not have to rebuild its atlas.
     */
    fun release() {
        detach()
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(display, context)
            }
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        config = null
    }
}

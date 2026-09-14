package com.iris.irisshell.terminal

import java.io.File

// Inspired by: github.com/rk134/ReTerminal (MkSession.kt, init-host.sh)
class ProotRunner(
    private val bootstrap: UbuntuBootstrap,
    private val nativeLibDir: String
) {
    val prootPath: String get() = bootstrap.prootFile.absolutePath
    val rootfsPath: String get() = bootstrap.rootfsDir.absolutePath
    private val baseDir: File get() = bootstrap.rootfsDir.parentFile!!
    private val tmpPath: String get() = File(baseDir, "tmp").absolutePath
    private val libPath: String get() = bootstrap.libDir.absolutePath

    private val linkerPath: String
        get() = if (File("/system/bin/linker64").exists()) "/system/bin/linker64" else "/system/bin/linker"

    private val loaderPath: String get() = "$nativeLibDir/libproot-loader.so"
    private val loader32Path: String get() = "$nativeLibDir/libproot-loader32.so"

    data class ProotCommand(
        val executable: String,
        val argv: List<String>,
        val cwd: String,
        val environment: List<String>
    )

    fun build(
        guestWorkDir: String? = null,
        shell: String = "/bin/zsh",
        ptyMode: Boolean = true,
        startCommand: String = "",
        environmentHooks: Map<String, String> = emptyMap(),
    ): ProotCommand {
        File(tmpPath).mkdirs()

        val wd = when {
            guestWorkDir != null -> guestWorkDir
            File(rootfsPath, "home").exists() -> "/home"
            else -> "/root"
        }

        val binds = buildBindMounts(ptyMode)

        val argv = mutableListOf<String>().apply {
            add(linkerPath)
            add(prootPath)
            add("--kill-on-exit")
            add("-w")
            add(wd)
            addAll(binds)
            add("-r")
            add(rootfsPath)
            add("-0")
            add("--link2symlink")
            add("--sysvipc")
            add("-L")
            if (startCommand.isNotBlank()) {
                startCommand.split(" ").filter { it.isNotBlank() }.forEach { add(it) }
            } else {
                add(shell)
                add("--login")
            }
        }

        val env = buildEnvironment() + environmentHooks.map { "${it.key}=${it.value}" }

        return ProotCommand(
            executable = linkerPath,
            argv = argv,
            cwd = baseDir.absolutePath,
            environment = env
        )
    }

    fun buildBashCommand(command: String, guestWorkDir: String? = null, shell: String = "/bin/zsh"): ProotCommand {
        val base = build(guestWorkDir, shell, ptyMode = false)
        val stdbufCmd = "stdbuf -oL $command"
        val argv = base.argv.dropLast(2) + listOf(shell, "-c", stdbufCmd)
        return base.copy(argv = argv)
    }

    private fun buildBindMounts(ptyMode: Boolean = true): List<String> {
        val binds = mutableListOf<String>()

        fun bind(guest: String, host: String? = null) {
            val src = host ?: guest
            if (File(src).exists()) {
                if (host != null) {
                    binds.add("-b"); binds.add("$src:$guest")
                } else {
                    binds.add("-b"); binds.add(src)
                }
            }
        }

        bind("/system")
        bind("/vendor")
        bind("/apex")
        bind("/odm")
        bind("/product")
        bind("/system_ext")
        bind("/linkerconfig/ld.config.txt")
        bind("/linkerconfig/com.android.art/ld.config.txt")
        bind("/plat_property_contexts")
        bind("/property_contexts")
        bind("/sdcard")
        bind("/storage")
        bind("/dev")
        bind("/data")
        bind("/dev/urandom", "/dev/random")
        bind("/proc")
        bind("/sys")

        // App data bind mount (for proot/libtalloc access)
        binds.add("-b"); binds.add(baseDir.parentFile!!.absolutePath)

        // Fake /proc/stat and /proc/vmstat
        val localDir = File(baseDir, "local")
        localDir.mkdirs()
        File(localDir, "stat").let { f ->
            if (!f.exists()) f.writeText(fakeProcStat)
        }
        File(localDir, "vmstat").let { f ->
            if (!f.exists()) f.writeText(fakeProcVmstat)
        }
        binds.add("-b"); binds.add("${localDir.absolutePath}/stat:/proc/stat")
        binds.add("-b"); binds.add("${localDir.absolutePath}/vmstat:/proc/vmstat")

        // FD mappings — only for PTY sessions (subprocess mode uses pipes,
        // and proot can't sanitize anonymous pipe paths)
        if (ptyMode) {
            if (File("/proc/self/fd").exists()) {
                binds.add("-b"); binds.add("/proc/self/fd:/dev/fd")
            }
            if (File("/proc/self/fd/0").exists()) {
                binds.add("-b"); binds.add("/proc/self/fd/0:/dev/stdin")
            }
            if (File("/proc/self/fd/1").exists()) {
                binds.add("-b"); binds.add("/proc/self/fd/1:/dev/stdout")
            }
            if (File("/proc/self/fd/2").exists()) {
                binds.add("-b"); binds.add("/proc/self/fd/2:/dev/stderr")
            }
        }

        // /dev/shm -> tmp
        binds.add("-b"); binds.add("$tmpPath:/dev/shm")

        return binds
    }

    private fun buildEnvironment(): List<String> {
        val env = mutableListOf<String>().apply {
            add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            add("HOME=/home")
            add("TERM=xterm-256color")
            add("LANG=C.UTF-8")
            add("SHELL=/bin/bash")
            add("TMPDIR=/tmp")
            add("PROOT_TMP_DIR=$tmpPath")
            add("LD_LIBRARY_PATH=$libPath")

            // Proot loader
            if (File(loaderPath).exists()) {
                add("PROOT_LOADER=$loaderPath")
            }
            if (File(loader32Path).exists()) {
                add("PROOT_LOADER32=$loader32Path")
            }
        }

        // Android system environment
        val androidEnv = mapOf(
            "ANDROID_ART_ROOT" to System.getenv("ANDROID_ART_ROOT"),
            "ANDROID_DATA" to System.getenv("ANDROID_DATA"),
            "ANDROID_I18N_ROOT" to System.getenv("ANDROID_I18N_ROOT"),
            "ANDROID_ROOT" to System.getenv("ANDROID_ROOT"),
            "ANDROID_RUNTIME_ROOT" to System.getenv("ANDROID_RUNTIME_ROOT"),
            "ANDROID_TZDATA_ROOT" to System.getenv("ANDROID_TZDATA_ROOT"),
            "BOOTCLASSPATH" to System.getenv("BOOTCLASSPATH"),
            "DEX2OATBOOTCLASSPATH" to System.getenv("DEX2OATBOOTCLASSPATH"),
            "EXTERNAL_STORAGE" to System.getenv("EXTERNAL_STORAGE")
        )
        for ((key, value) in androidEnv) {
            if (value != null) {
                env.add("$key=$value")
            }
        }

        // Pass through other host environment
        try {
            val systemEnv = System.getenv()
            for ((key, value) in systemEnv) {
                when (key) {
                    "PATH", "HOME", "TERM", "LANG", "SHELL", "TMPDIR",
                    "LD_LIBRARY_PATH", "LD_PRELOAD", "PREFIX",
                    "PROOT_LOADER", "PROOT_LOADER32", "PROOT_TMP_DIR",
                    "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH" -> {}
                    else -> if (key.startsWith("ANDROID_")) {} else env.add("$key=$value")
                }
            }
        } catch (_: Exception) {}

        return env
    }

    companion object {
        private val fakeProcStat = """
cpu  0 0 0 0 0 0 0 0 0 0
intr 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
ctxt 0
btime 0
processes 0
procs_running 1
procs_blocked 0
""".trimIndent()

        private val fakeProcVmstat = """
nr_free_pages 0
nr_inactive_anon 0
nr_active_anon 0
nr_inactive_file 0
nr_active_file 0
nr_unevictable 0
nr_mlock 0
nr_anon_pages 0
nr_mapped 0
nr_file_pages 0
nr_dirty 0
nr_writeback 0
nr_slab_reclaimable 0
nr_slab_unreclaimable 0
nr_page_table_pages 0
nr_kernel_stack 0
nr_unstable 0
nr_bounce 0
nr_vmscan_write 0
nr_vmscan_immediate_reclaim 0
nr_writeback_temp 0
nr_isolated_anon 0
nr_isolated_file 0
nr_shmem 0
nr_dirtied 0
nr_written 0
nr_pages_scanned 0
pgpgin 0
pgpgout 0
pswpin 0
pswpout 0
pgalloc_dma 0
pgalloc_dma32 0
pgalloc_normal 0
pgalloc_movable 0
pgfree 0
pgactivate 0
pgdeactivate 0
pgfault 0
pgmajfault 0
pgrefill_dma 0
pgrefill_dma32 0
pgrefill_normal 0
pgrefill_movable 0
pgsteal_kswapd_dma 0
pgsteal_kswapd_dma32 0
pgsteal_kswapd_normal 0
pgsteal_kswapd_movable 0
pgscan_kswapd_dma 0
pgscan_kswapd_dma32 0
pgscan_kswapd_normal 0
pgscan_kswapd_movable 0
pgsteal_direct_dma 0
pgsteal_direct_dma32 0
pgsteal_direct_normal 0
pgsteal_direct_movable 0
pgscan_direct_dma 0
pgscan_direct_dma32 0
pgscan_direct_normal 0
pgscan_direct_movable 0
oom_kill 0
""".trimIndent()
    }
}

package io.github.ccch1mneyyy.dsh_app.runtime

import android.content.Context
import java.io.File

/**
 * PRoot's `--link2symlink` loader, staged on app_data_file.
 *
 * The runtime bundle ships it as `jniLib/libproot-loader.so`, i.e. `apk_data_file`. PRoot arms
 * `PR_SET_NO_NEW_PRIVS` before it execs the loader, and Android 14+ refuses a `no_new_privs`
 * exec of `apk_data_file`: execve returns EACCES with no audited denial, so PRoot can only
 * report `execve("/usr/bin/env"): Permission denied`. `app_data_file` is executable in that
 * state (it is where PRoot's traced execs already land), so the same binary is copied under
 * `filesDir` once per build and `PROOT_LOADER` is pointed there instead.
 *
 * Host path only: the guest never sees this file, and it carries no privilege of its own.
 */
internal object ProotLoader {
    private const val NAME = "libproot-loader.so"
    private const val COPY_BUFFER = 1 shl 16

    /** Absolute host path of an executable copy, or null when this build has no loader. */
    fun stage(context: Context): String? {
        val nativeDir = context.applicationInfo.nativeLibraryDir ?: return null
        val source = File(nativeDir, NAME)
        if (!source.isFile) return null

        val dir = File(context.filesDir, "proot-loader")
        val target = File(dir, "loader")
        // Re-stage after an APK update: the bundled copy then has a newer modification time.
        if (target.isFile && target.length() == source.length() &&
            target.lastModified() >= source.lastModified() && target.canExecute()
        ) {
            return target.absolutePath
        }

        return runCatching { copy(source, dir, target) }.getOrNull()
    }

    private fun copy(source: File, dir: File, target: File): String? {
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val temp = File(dir, ".loader.${System.nanoTime()}.tmp")
        try {
            source.inputStream().use { input ->
                temp.outputStream().use { output -> input.copyTo(output, COPY_BUFFER) }
            }
            // Only the owner may read/write/execute the staged binary.
            temp.setReadable(false, false)
            temp.setWritable(false, false)
            temp.setExecutable(false, false)
            val executable = temp.setReadable(true, true) && temp.setWritable(true, true) &&
                temp.setExecutable(true, true)
            if (!executable) return null
            // rename(2) replaces `target` atomically; a half-written loader never goes live.
            if (!temp.renameTo(target)) return null
        } finally {
            if (temp.exists()) temp.delete()
        }
        return target.absolutePath
    }
}
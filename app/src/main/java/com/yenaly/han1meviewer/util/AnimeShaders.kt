package com.yenaly.han1meviewer.util

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


object AnimeShaders {
    const val SHADERS_DIRECTORY = "shaders"
    // 超分辨率滤镜 (质量) A
    val mpvSuperResolutionArray = arrayOf(
        "Anime4K_Clamp_Highlights.glsl",
        "Anime4K_Restore_CNN_VL.glsl",
        "Anime4K_Upscale_CNN_x2_VL.glsl",
        "Anime4K_AutoDownscalePre_x2.glsl",
        "Anime4K_AutoDownscalePre_x4.glsl",
        "Anime4K_Upscale_CNN_x2_M.glsl"
    )
    // 超分辨率滤镜 (效率) A+A
    val mpvSuperResolutionLiteArray = arrayOf(
        "Anime4K_Clamp_Highlights.glsl",
        "Anime4K_Restore_CNN_M.glsl",
        "Anime4K_Restore_CNN_S.glsl",
        "Anime4K_Upscale_CNN_x2_M.glsl",
        "Anime4K_AutoDownscalePre_x2.glsl",
        "Anime4K_AutoDownscalePre_x4.glsl",
        "Anime4K_Upscale_CNN_x2_S.glsl"
    )

    /**
     * 从 assets/shaders/ 复制所有文件到应用私有目录的 shaders/ 文件夹
     * @return 成功复制的文件数量，-1 表示出错
     */
    fun copyShaderAssets(context: Context): Int {
        try {
            val targetDir = File(context.filesDir, SHADERS_DIRECTORY)
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            val assetManager = context.assets
            val assetFiles = assetManager.list(SHADERS_DIRECTORY) ?: return 0

            var copiedCount = 0
            for (filename in assetFiles) {
                val targetFile = File(targetDir, filename)
                assetManager.open("$SHADERS_DIRECTORY/$filename").use { inputStream ->
                    FileOutputStream(targetFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                        copiedCount++
                    }
                }
            }

            // 清理孤儿文件：App 升级后 assets 里已删除的 shader 不应残留在磁盘上，
            // 否则 getShader 拼出的路径列表会引用不存在/过期的文件。
            val assetSet = assetFiles.toHashSet()
            targetDir.listFiles { f -> f.isFile }?.forEach { f ->
                if (f.name !in assetSet) {
                    f.delete()
                }
            }
            return copiedCount
        } catch (e: IOException) {
            e.printStackTrace()
            return -1
        }
    }

    /**
     * 获取应用私有目录的 shaders/ 文件夹路径
     */
    fun getShader(context: Context, type: Int): String {
        val shadersDir = File(context.filesDir, SHADERS_DIRECTORY)
        if (!shadersDir.exists()) {
            throw IllegalStateException("Shader 文件夹不存在: $shadersDir")
        }

        val shaderFiles = when (type) {
            // 效率
            1 -> mpvSuperResolutionLiteArray
            // 质量
            2 -> mpvSuperResolutionArray
            else -> throw IllegalArgumentException("未知 Shader 类型: $type")
        }
        return shaderFiles.joinToString(separator = ":") { shaderFile ->
            File(shadersDir, shaderFile).absolutePath
        }
    }

    /**
     * 超分辨率档位枚举，替代魔法 int（0=关 1=效率 2=质量），
     * 与 Preferences.superResolutionLevel 及 HJzvdStd 的字符串数组索引保持一致。
     */
    const val SR_LEVEL_OFF = 0
    const val SR_LEVEL_PERFORMANCE = 1
    const val SR_LEVEL_QUALITY = 2

    fun copyCertAssets(context: Context): Int{
        try {
            val assetName = "cacert.pem"
            val outputFile = File(context.filesDir, assetName)
            if (!outputFile.exists()){
                val assetManager = context.assets
                assetManager.open(assetName).use { inputStream ->
                    FileOutputStream(outputFile).use { outputStream ->
                        val buffer = ByteArray(1024)
                        var read: Int
                        while (inputStream.read(buffer).also { read = it } != -1){
                            outputStream.write(buffer, 0, read)
                        }
                        outputStream.flush()
                    }
                }
            }
            return 1
        }catch (e: IOException){
            e.printStackTrace()
            return -1
        }
    }

    fun getCert(context: Context): String {
        val certFile = File(context.filesDir, "cacert.pem")
        if (!certFile.exists()) {
            throw IllegalStateException("根证书文件不存在: $certFile")
        }
        return certFile.absolutePath
    }

}
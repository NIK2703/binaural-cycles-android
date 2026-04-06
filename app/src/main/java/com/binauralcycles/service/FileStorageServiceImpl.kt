package com.binauralcycles.service

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.binaural.core.domain.model.BinauralPreset
import com.binaural.core.domain.service.FileStorageService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Реализация FileStorageService для Android.
 * Использует Context для работы с файловой системой.
 */
@Singleton
class FileStorageServiceImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : FileStorageService {
    
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }
    
    override fun exportToJson(preset: BinauralPreset): String? {
        return try {
            json.encodeToString(preset)
        } catch (e: Exception) {
            android.util.Log.e("FileStorageService", "Failed to export preset", e)
            null
        }
    }
    
    override fun importFromJson(jsonString: String): BinauralPreset? {
        return try {
            json.decodeFromString<BinauralPreset>(jsonString)
        } catch (e: Exception) {
            android.util.Log.e("FileStorageService", "Failed to import preset", e)
            null
        }
    }
    
    override suspend fun createExportFile(presetName: String): String? {
        // Для Android SAF (Storage Access Framework) файл создается при записи
        // Этот метод не нужен, возвращаем null
        return null
    }
    
    override suspend fun writeToUri(uriString: String, data: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)
            context.contentResolver.openOutputStream(uri, "wt")?.use { outputStream ->
                outputStream.bufferedWriter().use { writer ->
                    writer.write(data)
                }
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("FileStorageService", "Failed to write to URI", e)
            false
        }
    }
    
    override suspend fun readFromUri(uriString: String): String? {
        return try {
            val uri = Uri.parse(uriString)
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.bufferedReader().readText()
            }
        } catch (e: Exception) {
            android.util.Log.e("FileStorageService", "Failed to read from URI", e)
            null
        }
    }
}
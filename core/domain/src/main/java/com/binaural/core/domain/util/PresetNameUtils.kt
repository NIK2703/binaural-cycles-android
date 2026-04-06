package com.binaural.core.domain.util

/**
 * Утилиты для работы с именами пресетов.
 */
object PresetNameUtils {
    
    /**
     * Генерирует уникальное имя на основе базового имени.
     * Добавляет суффикс (1), (2) и т.д. если имя уже существует.
     * 
     * @param baseName базовое имя
     * @param existingNames множество уже существующих имён
     * @return уникальное имя
     */
    fun generateUniqueName(baseName: String, existingNames: Set<String>): String {
        if (baseName !in existingNames) {
            return baseName
        }
        
        // Проверяем, есть ли уже суффикс (N) в имени
        val suffixRegex = """^(.+?)\s*\((\d+)\)$""".toRegex()
        val match = suffixRegex.find(baseName)
        
        val nameBase = match?.groupValues?.get(1)?.trim() ?: baseName
        var counter = match?.groupValues?.get(2)?.toIntOrNull() ?: 1
        
        while (true) {
            val newName = "$nameBase (${counter + 1})"
            if (newName !in existingNames) {
                return newName
            }
            counter++
        }
    }
}
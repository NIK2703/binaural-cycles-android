package com.binaural.core.domain.usecase

import com.binaural.core.domain.model.BinauralPreset
import com.binaural.core.domain.model.FrequencyCurve
import com.binaural.core.domain.model.RelaxationModeSettings
import com.binaural.core.domain.test.FakeFileStorageService
import com.binaural.core.domain.test.FakePresetRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Тесты для ExportPresetUseCase.
 */
class ExportPresetUseCaseTest {
    
    private lateinit var presetRepository: FakePresetRepository
    private lateinit var fileStorageService: FakeFileStorageService
    private lateinit var exportPresetUseCase: ExportPresetUseCase
    
    @Before
    fun setup() {
        presetRepository = FakePresetRepository()
        fileStorageService = FakeFileStorageService()
        exportPresetUseCase = ExportPresetUseCase(
            presetRepository = presetRepository,
            fileStorageService = fileStorageService
        )
    }
    
    @Test
    fun `export existing preset returns json`() = runTest {
        val preset = createTestPreset("preset-1", "Test Preset")
        presetRepository.addPresetDirectly(preset)
        
        val result = exportPresetUseCase("preset-1")
        
        assertNotNull(result)
        assertTrue(result!!.contains("Test Preset"))
    }
    
    @Test
    fun `export non-existing preset returns null`() = runTest {
        val result = exportPresetUseCase("non-existing")
        
        assertNull(result)
    }
    
    @Test
    fun `write to uri returns true for existing preset`() = runTest {
        val preset = createTestPreset("preset-1", "Test Preset")
        presetRepository.addPresetDirectly(preset)
        val uriString = "file://test/export.json"
        
        val result = exportPresetUseCase.writeToUri(uriString, "preset-1")
        
        assertTrue(result)
    }
    
    @Test
    fun `write to uri returns false for non-existing preset`() = runTest {
        val uriString = "file://test/export.json"
        
        val result = exportPresetUseCase.writeToUri(uriString, "non-existing")
        
        assertFalse(result)
    }
    
    @Test
    fun `write to uri stores data in storage service`() = runTest {
        val preset = createTestPreset("preset-1", "Test Preset")
        presetRepository.addPresetDirectly(preset)
        val uriString = "file://test/export.json"
        
        exportPresetUseCase.writeToUri(uriString, "preset-1")
        
        val storedData = fileStorageService.getFileContent(uriString)
        assertNotNull(storedData)
    }
    
    private fun createTestPreset(id: String, name: String): BinauralPreset {
        return BinauralPreset(
            id = id,
            name = name,
            frequencyCurve = FrequencyCurve.defaultCurve(),
            relaxationModeSettings = RelaxationModeSettings()
        )
    }
}
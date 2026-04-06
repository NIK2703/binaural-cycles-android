package com.binaural.core.domain.usecase

import com.binaural.core.domain.model.BinauralPreset
import com.binaural.core.domain.model.FrequencyCurve
import com.binaural.core.domain.model.RelaxationModeSettings
import com.binaural.core.domain.test.FakeFileStorageService
import com.binaural.core.domain.test.FakePresetRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Тесты для ImportPresetUseCase.
 */
class ImportPresetUseCaseTest {
    
    private lateinit var presetRepository: FakePresetRepository
    private lateinit var fileStorageService: FakeFileStorageService
    private lateinit var duplicatePresetUseCase: DuplicatePresetUseCase
    private lateinit var importPresetUseCase: ImportPresetUseCase
    
    @Before
    fun setup() {
        presetRepository = FakePresetRepository()
        fileStorageService = FakeFileStorageService()
        duplicatePresetUseCase = DuplicatePresetUseCase(presetRepository)
        importPresetUseCase = ImportPresetUseCase(
            presetRepository = presetRepository,
            fileStorageService = fileStorageService,
            duplicatePresetUseCase = duplicatePresetUseCase
        )
    }
    
    @Test
    fun `import from valid json returns preset`() = runTest {
        val preset = createTestPreset("original-id", "Test Preset")
        val json = fileStorageService.exportToJson(preset)!!
        
        val result = importPresetUseCase(json)
        
        assertNotNull(result)
        assertEquals("Test Preset", result?.name)
    }
    
    @Test
    fun `import from invalid json returns null`() = runTest {
        val result = importPresetUseCase("invalid json")
        
        assertNull(result)
    }
    
    @Test
    fun `import generates new id`() = runTest {
        val preset = createTestPreset("original-id", "Test Preset")
        val json = fileStorageService.exportToJson(preset)!!
        
        val result = importPresetUseCase(json)
        
        assertNotNull(result)
        assertNotEquals("original-id", result?.id)
    }
    
    @Test
    fun `import adds preset to repository`() = runTest {
        val preset = createTestPreset("original-id", "Test Preset")
        val json = fileStorageService.exportToJson(preset)!!
        
        importPresetUseCase(json)
        
        val presets = presetRepository.getPresets().first()
        assertEquals(1, presets.size)
    }
    
    @Test
    fun `import generates unique name if duplicate exists`() = runTest {
        val existingPreset = createTestPreset("existing-id", "Test Preset")
        presetRepository.addPresetDirectly(existingPreset)
        
        val importPreset = createTestPreset("import-id", "Test Preset")
        val json = fileStorageService.exportToJson(importPreset)!!
        
        val result = importPresetUseCase(json)
        
        assertNotNull(result)
        assertTrue(result?.name?.startsWith("Test Preset") == true)
        assertNotEquals("Test Preset", result?.name)
    }
    
    @Test
    fun `import from uri returns preset`() = runTest {
        val preset = createTestPreset("uri-preset", "URI Preset")
        val uriString = fileStorageService.preparePresetForImport(preset)
        
        val result = importPresetUseCase.fromUri(uriString)
        
        assertNotNull(result)
    }
    
    @Test
    fun `import from invalid uri returns null`() = runTest {
        val result = importPresetUseCase.fromUri("invalid://uri")
        
        assertNull(result)
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
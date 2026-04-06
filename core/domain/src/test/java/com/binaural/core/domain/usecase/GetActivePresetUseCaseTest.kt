package com.binaural.core.domain.usecase

import com.binaural.core.domain.model.BinauralPreset
import com.binaural.core.domain.model.FrequencyCurve
import com.binaural.core.domain.model.PlaybackState
import com.binaural.core.domain.model.RelaxationModeSettings
import com.binaural.core.domain.test.FakePlaybackStateRepository
import com.binaural.core.domain.test.FakePresetRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Тесты для GetActivePresetUseCase.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GetActivePresetUseCaseTest {
    
    private lateinit var presetRepository: FakePresetRepository
    private lateinit var playbackStateRepository: FakePlaybackStateRepository
    private lateinit var getActivePresetUseCase: GetActivePresetUseCase
    
    private val testPreset1 = BinauralPreset(
        id = "preset-1",
        name = "Test Preset 1",
        frequencyCurve = FrequencyCurve.defaultCurve(),
        relaxationModeSettings = RelaxationModeSettings()
    )
    
    private val testPreset2 = BinauralPreset(
        id = "preset-2",
        name = "Test Preset 2",
        frequencyCurve = FrequencyCurve.defaultCurve(),
        relaxationModeSettings = RelaxationModeSettings()
    )
    
    @Before
    fun setup() {
        presetRepository = FakePresetRepository()
        playbackStateRepository = FakePlaybackStateRepository()
        getActivePresetUseCase = GetActivePresetUseCase(presetRepository, playbackStateRepository)
    }
    
    @Test
    fun `invoke returns null when no active preset`() = runTest {
        val result = getActivePresetUseCase()
        
        assertNull(result)
    }
    
    @Test
    fun `invoke returns preset when active preset is set`() = runTest {
        // Добавляем пресет и устанавливаем как активный
        presetRepository.addPresetDirectly(testPreset1)
        presetRepository.setActivePresetId(testPreset1.id)
        
        val result = getActivePresetUseCase()
        
        assertNotNull(result)
        assertEquals(testPreset1.id, result?.id)
        assertEquals(testPreset1.name, result?.name)
    }
    
    @Test
    fun `invoke returns correct preset after changing active`() = runTest {
        // Добавляем два пресета
        presetRepository.addPresetDirectly(testPreset1)
        presetRepository.addPresetDirectly(testPreset2)
        
        // Устанавливаем первый как активный
        presetRepository.setActivePresetId(testPreset1.id)
        
        var result = getActivePresetUseCase()
        assertEquals(testPreset1.id, result?.id)
        
        // Меняем на второй
        presetRepository.setActivePresetId(testPreset2.id)
        
        result = getActivePresetUseCase()
        assertEquals(testPreset2.id, result?.id)
    }
    
    @Test
    fun `invoke returns null when active preset id is set but preset not found`() = runTest {
        // Устанавливаем ID несуществующего пресета
        presetRepository.setActivePresetId("non-existent-id")
        
        val result = getActivePresetUseCase()
        
        assertNull(result)
    }
    
    @Test
    fun `invoke returns null after clearing active preset`() = runTest {
        // Добавляем пресет и устанавливаем как активный
        presetRepository.addPresetDirectly(testPreset1)
        presetRepository.setActivePresetId(testPreset1.id)
        
        assertNotNull(getActivePresetUseCase())
        
        // Сбрасываем активный пресет
        presetRepository.setActivePresetId(null)
        
        assertNull(getActivePresetUseCase())
    }
}
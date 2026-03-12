#include "AudioGenerator.h"
#include <algorithm>
#include <cmath>

namespace binaural {

AudioGenerator::AudioGenerator() {
    // Инициализация wavetable при создании генератора
    Wavetable::initialize();
}

void AudioGenerator::setSampleRate(int sampleRate) {
    m_sampleRate = sampleRate;
}

void AudioGenerator::resetState(GeneratorState& state) {
    state.leftPhase = 0.0;
    state.rightPhase = 0.0;
    state.channelsSwapped = false;
    state.lastSwapElapsedMs = 0;
    state.totalSamplesGenerated = 0;
    state.currentFadeOperation = GeneratorState::FadeOperation::NONE;
    state.isFadingOut = true;
    state.fadeStartSample = 0;
}

int AudioGenerator::findIntervalIndex(const std::vector<FrequencyPoint>& sortedPoints, int32_t targetSeconds) const {
    if (sortedPoints.empty()) return -1;
    
    // Быстрая проверка границ
    const int32_t firstSeconds = sortedPoints.front().timeSeconds;
    const int32_t lastSeconds = sortedPoints.back().timeSeconds;
    
    if (targetSeconds < firstSeconds || targetSeconds >= lastSeconds) {
        return -1; // Переход через полночь
    }
    
    // Бинарный поиск
    int left = 0;
    int right = static_cast<int>(sortedPoints.size()) - 1;
    
    while (left < right - 1) {
        const int mid = (left + right) >> 1;
        if (sortedPoints[mid].timeSeconds <= targetSeconds) {
            left = mid;
        } else {
            right = mid;
        }
    }
    
    return left;
}

void AudioGenerator::interpolateChannelFrequencies(
    const FrequencyCurve& curve,
    int32_t timeSeconds,
    double& lowerFreq,
    double& upperFreq
) const {
    if (curve.points.size() < 2) {
        lowerFreq = 200.0;
        upperFreq = 210.0;
        return;
    }
    
    // Сортированные точки (предполагаем, что уже отсортированы по времени)
    const auto& points = curve.points;
    
    // Находим интервал
    const int intervalIndex = findIntervalIndex(points, timeSeconds);
    
    int leftIndex, rightIndex;
    bool isWrapping = false;
    
    if (intervalIndex == -1) {
        // Переход через полночь
        leftIndex = static_cast<int>(points.size()) - 1;
        rightIndex = 0;
        isWrapping = true;
    } else {
        leftIndex = intervalIndex;
        rightIndex = intervalIndex + 1;
    }
    
    const auto& leftPoint = points[leftIndex];
    const auto& rightPoint = points[rightIndex];
    
    // Вычисляем нормализованную позицию t в интервале [0, 1]
    int32_t t1 = leftPoint.timeSeconds;
    int32_t t2 = isWrapping ? rightPoint.timeSeconds + SECONDS_PER_DAY : rightPoint.timeSeconds;
    int32_t t = timeSeconds;
    
    if (isWrapping && t < t1) {
        t += SECONDS_PER_DAY;
    }
    
    if (t2 == t1) {
        lowerFreq = leftPoint.carrierFrequency - leftPoint.beatFrequency / 2.0;
        upperFreq = leftPoint.carrierFrequency + leftPoint.beatFrequency / 2.0;
        return;
    }
    
    const double ratio = static_cast<double>(t - t1) / (t2 - t1);
    
    // Вычисляем значения частот каналов в точках
    auto getLowerFreq = [](const FrequencyPoint& p) {
        return p.carrierFrequency - p.beatFrequency / 2.0;
    };
    auto getUpperFreq = [](const FrequencyPoint& p) {
        return p.carrierFrequency + p.beatFrequency / 2.0;
    };
    
    // Получаем 4 соседние точки для сплайна
    const int size = static_cast<int>(points.size());
    auto getNeighbor = [&](int index, int offset) -> const FrequencyPoint* {
        int neighborIndex = index + offset;
        if (neighborIndex < 0) {
            return isWrapping ? &points.back() : &points.front();
        } else if (neighborIndex >= size) {
            return isWrapping ? &points.front() : &points.back();
        }
        return &points[neighborIndex];
    };
    
    const auto* p0Ptr = getNeighbor(leftIndex, -1);
    const auto* p3Ptr = getNeighbor(rightIndex, 1);
    
    // Интерполируем нижнюю частоту
    double lowerP0 = getLowerFreq(*p0Ptr);
    double lowerP1 = getLowerFreq(leftPoint);
    double lowerP2 = getLowerFreq(rightPoint);
    double lowerP3 = getLowerFreq(*p3Ptr);
    lowerFreq = Interpolation::interpolate(
        curve.interpolationType, lowerP0, lowerP1, lowerP2, lowerP3, ratio, curve.splineTension
    );
    
    // Интерполируем верхнюю частоту
    double upperP0 = getUpperFreq(*p0Ptr);
    double upperP1 = getUpperFreq(leftPoint);
    double upperP2 = getUpperFreq(rightPoint);
    double upperP3 = getUpperFreq(*p3Ptr);
    upperFreq = Interpolation::interpolate(
        curve.interpolationType, upperP0, upperP1, upperP2, upperP3, ratio, curve.splineTension
    );
    
    // Гарантируем неотрицательные частоты
    lowerFreq = std::max(0.0, lowerFreq);
    upperFreq = std::max(0.0, upperFreq);
}

std::pair<double, double> AudioGenerator::getChannelFrequenciesAtTime(
    const BinauralConfig& config,
    int32_t baseTimeSeconds,
    int64_t offsetMs
) const {
    // Вычисляем время с учётом смещения
    double offsetSeconds = offsetMs / 1000.0;
    int32_t totalSeconds = static_cast<int32_t>(baseTimeSeconds + offsetSeconds);
    
    // Нормализуем в пределах суток
    totalSeconds = ((totalSeconds % SECONDS_PER_DAY) + SECONDS_PER_DAY) % SECONDS_PER_DAY;
    
    double lowerFreq, upperFreq;
    interpolateChannelFrequencies(config.curve, totalSeconds, lowerFreq, upperFreq);
    
    return {lowerFreq, upperFreq};
}

std::pair<double, double> AudioGenerator::calculateNormalizedAmplitudes(
    double leftFreq,
    double rightFreq,
    const BinauralConfig& config,
    const FrequencyCurve& curve
) const {
    double leftAmplitude = 1.0;
    double rightAmplitude = 1.0;
    
    const double strength = std::clamp(config.volumeNormalizationStrength, 0.0f, 2.0f);
    
    switch (config.normalizationType) {
        case NormalizationType::NONE:
            // Без нормализации
            break;
            
        case NormalizationType::CHANNEL: {
            // Канальная нормализация (уравнивание между левым и правым каналом)
            const double minFreq = std::min(leftFreq, rightFreq);
            const double leftNormalized = minFreq / leftFreq;
            const double rightNormalized = minFreq / rightFreq;
            leftAmplitude = fastPow(leftNormalized, strength);
            rightAmplitude = fastPow(rightNormalized, strength);
            break;
        }
        
        case NormalizationType::TEMPORAL: {
            // Временная нормализация
            const double leftNormalized = leftFreq > 0 ? curve.minLowerFreq / leftFreq : 1.0;
            const double rightNormalized = rightFreq > 0 ? curve.minUpperFreq / rightFreq : 1.0;
            leftAmplitude = fastPow(leftNormalized, strength);
            rightAmplitude = fastPow(rightNormalized, strength);
            break;
        }
    }
    
    return {leftAmplitude, rightAmplitude};
}

GenerateResult AudioGenerator::generateBuffer(
    float* buffer,
    int samplesPerChannel,
    const BinauralConfig& config,
    GeneratorState& state,
    int32_t timeSeconds,
    int64_t elapsedMs,
    int frequencyUpdateIntervalMs
) {
    GenerateResult result;
    
    // Используем переданный интервал обновления частот для интерполяции
    // Это обеспечивает точное соответствие настройкам пользователя
    const int32_t intervalSeconds = frequencyUpdateIntervalMs / 1000;
    
    // Начальные частоты (точка графика для текущего момента)
    double startLeftFreq, startRightFreq;
    interpolateChannelFrequencies(config.curve, timeSeconds, startLeftFreq, startRightFreq);
    
    // Конечные частоты (точка графика, отстоящая на интервал обновления)
    double endLeftFreq, endRightFreq;
    int32_t endTimeSeconds = timeSeconds + intervalSeconds;
    endTimeSeconds = ((endTimeSeconds % SECONDS_PER_DAY) + SECONDS_PER_DAY) % SECONDS_PER_DAY;
    interpolateChannelFrequencies(config.curve, endTimeSeconds, endLeftFreq, endRightFreq);
    
    // Начальные амплитуды
    auto [startLeftAmplitude, startRightAmplitude] = calculateNormalizedAmplitudes(
        startLeftFreq, startRightFreq, config, config.curve
    );
    
    // Конечные амплитуды
    auto [endLeftAmplitude, endRightAmplitude] = calculateNormalizedAmplitudes(
        endLeftFreq, endRightFreq, config, config.curve
    );
    
    // Предвычисление констант
    const double twoPiOverSampleRate = TWO_PI / m_sampleRate;
    const float baseVolumeFactor = 0.5f * config.volume;
    
    // Фазовые инкременты
    double leftOmega = twoPiOverSampleRate * startLeftFreq;
    double rightOmega = twoPiOverSampleRate * startRightFreq;
    const double endLeftOmega = twoPiOverSampleRate * endLeftFreq;
    const double endRightOmega = twoPiOverSampleRate * endRightFreq;
    
    // Шаги изменения фазовых инкрементов
    const double omegaStepLeft = (endLeftOmega - leftOmega) / samplesPerChannel;
    const double omegaStepRight = (endRightOmega - rightOmega) / samplesPerChannel;
    
    // Текущие амплитуды
    double leftAmplitude = startLeftAmplitude;
    double rightAmplitude = startRightAmplitude;
    
    // Шаги изменения амплитуд
    const double ampStepLeft = (endLeftAmplitude - startLeftAmplitude) / samplesPerChannel;
    const double ampStepRight = (endRightAmplitude - startRightAmplitude) / samplesPerChannel;
    
    // Параметры fade
    const int channelSwapFadeDurationSamples = static_cast<int>(
        std::max<int64_t>(config.channelSwapFadeDurationMs, 100LL) * m_sampleRate / 1000
    );
    
    // Локальные переменные для состояния
    bool localIsFadingOut = state.isFadingOut;
    int64_t localFadeStartSample = state.fadeStartSample;
    bool localChannelsSwapped = state.channelsSwapped;
    GeneratorState::FadeOperation localFadeOperation = state.currentFadeOperation;
    int64_t swapExecutedAtSample = -1;
    
    const bool channelSwapEnabled = config.channelSwapEnabled;
    const bool channelsSwappedAtStart = state.channelsSwapped;
    
    // Проверка перестановки каналов
    if (localFadeOperation == GeneratorState::FadeOperation::NONE && channelSwapEnabled) {
        const int64_t swapIntervalMs = config.channelSwapIntervalSec * 1000LL;
        if (elapsedMs - state.lastSwapElapsedMs >= swapIntervalMs) {
            if (config.channelSwapFadeEnabled) {
                localFadeOperation = GeneratorState::FadeOperation::CHANNEL_SWAP;
                localIsFadingOut = true;
                localFadeStartSample = state.totalSamplesGenerated;
                state.lastSwapElapsedMs = elapsedMs;
            } else {
                localChannelsSwapped = !localChannelsSwapped;
                state.lastSwapElapsedMs = elapsedMs;
                result.channelsSwapped = true;
            }
        }
    }
    
    // Основной цикл генерации
    for (int i = 0; i < samplesPerChannel; ++i) {
        const int64_t currentSample = state.totalSamplesGenerated + i;
        double fadeMultiplier = 1.0;
        
        // Обработка fade
        if (localFadeOperation != GeneratorState::FadeOperation::NONE) {
            const int fadeDurationSamples = channelSwapFadeDurationSamples;
            const int64_t elapsedSamples = currentSample - localFadeStartSample;
            const double progress = static_cast<double>(elapsedSamples) / fadeDurationSamples;
            
            if (localIsFadingOut) {
                if (progress >= 1.0) {
                    fadeMultiplier = 0.0;
                    if (localFadeOperation == GeneratorState::FadeOperation::CHANNEL_SWAP && swapExecutedAtSample < 0) {
                        swapExecutedAtSample = currentSample;
                        localChannelsSwapped = !localChannelsSwapped;
                        result.channelsSwapped = true;
                        localIsFadingOut = false;
                        localFadeStartSample = currentSample;
                    }
                } else if (progress >= 0.0) {
                    fadeMultiplier = 1.0 - progress;
                }
            } else {
                if (progress >= 1.0) {
                    fadeMultiplier = 1.0;
                    result.fadePhaseCompleted = true;
                } else if (progress >= 0.0) {
                    fadeMultiplier = progress;
                }
            }
        }
        
        // Генерация синусов через wavetable
        const float leftSample = Wavetable::fastSin(state.leftPhase);
        const float rightSample = Wavetable::fastSin(state.rightPhase);
        
        // Обновление фаз (branch-free)
        state.leftPhase += leftOmega;
        while (state.leftPhase >= TWO_PI) state.leftPhase -= TWO_PI;
        
        state.rightPhase += rightOmega;
        while (state.rightPhase >= TWO_PI) state.rightPhase -= TWO_PI;
        
        // Вычисление амплитуд
        const float baseAmplitude = baseVolumeFactor * static_cast<float>(fadeMultiplier);
        const float leftAmp = baseAmplitude * static_cast<float>(leftAmplitude);
        const float rightAmp = baseAmplitude * static_cast<float>(rightAmplitude);
        
        // Обновление инкрементов
        leftOmega += omegaStepLeft;
        rightOmega += omegaStepRight;
        leftAmplitude += ampStepLeft;
        rightAmplitude += ampStepRight;
        
        // Определение перестановки каналов для текущего сэмпла
        const bool swapForSample = channelSwapEnabled && 
            ((swapExecutedAtSample >= 0 && currentSample >= swapExecutedAtSample) ? 
             localChannelsSwapped : channelsSwappedAtStart);
        
        // Запись в буфер (interleaved stereo)
        if (swapForSample) {
            buffer[i * 2] = rightSample * rightAmp;
            buffer[i * 2 + 1] = leftSample * leftAmp;
        } else {
            buffer[i * 2] = leftSample * leftAmp;
            buffer[i * 2 + 1] = rightSample * rightAmp;
        }
    }
    
    // Обновление состояния
    state.channelsSwapped = localChannelsSwapped;
    state.isFadingOut = localIsFadingOut;
    state.fadeStartSample = localFadeStartSample;
    state.currentFadeOperation = localFadeOperation;
    state.totalSamplesGenerated += samplesPerChannel;
    
    // Текущие частоты для UI
    result.currentBeatFreq = (startRightFreq + endRightFreq) / 2.0 - (startLeftFreq + endLeftFreq) / 2.0;
    result.currentCarrierFreq = ((startLeftFreq + endLeftFreq) / 2.0 + (startRightFreq + endRightFreq) / 2.0) / 2.0;
    
    return result;
}

} // namespace binaural
#include "Wavetable.h"
#include <cstring>

#ifdef __ANDROID__
#include <malloc.h>
#endif

namespace binaural {

// Статические члены
float* Wavetable::s_sineTable = nullptr;
int Wavetable::s_tableSize = DEFAULT_TABLE_SIZE;
int Wavetable::s_tableSizeMask = DEFAULT_TABLE_SIZE - 1;
float Wavetable::s_scaleFactor = static_cast<float>(DEFAULT_TABLE_SIZE) / TWO_PI;
size_t Wavetable::s_allocatedSize = 0;

void Wavetable::initialize(int size) {
    // Освобождаем предыдущую таблицу если есть
    release();
    
    s_tableSize = size;
    s_tableSizeMask = size - 1;
    s_scaleFactor = static_cast<float>(size) / TWO_PI;
    
    // Выделяем память с выравниванием 32 байта для оптимального SIMD доступа
    // Добавляем 4 дополнительных элемента для безопасной интерполяции (без mask)
    const size_t numElements = size + 4;
    s_allocatedSize = numElements * sizeof(float);
    
#ifdef __ANDROID__
    // Android: используем memalign для выравнивания
    s_sineTable = static_cast<float*>(memalign(32, s_allocatedSize));
#else
    // POSIX: используем posix_memalign
    void* ptr = nullptr;
    posix_memalign(&ptr, 32, s_allocatedSize);
    s_sineTable = static_cast<float*>(ptr);
#endif
    
    if (!s_sineTable) {
        // Fallback на обычный malloc если выравнивание не удалось
        s_sineTable = static_cast<float*>(std::malloc(s_allocatedSize));
    }
    
    // Заполняем таблицу синусами
    for (int i = 0; i < size; ++i) {
        s_sineTable[i] = static_cast<float>(std::sin(TWO_PI * static_cast<double>(i) / size));
    }
    
    // Дублируем первые 4 значения в конце для упрощения интерполяции
    // Это позволяет избежать masking при доступе к index + 1
    for (int i = 0; i < 4; ++i) {
        s_sineTable[size + i] = s_sineTable[i];
    }
}

void Wavetable::release() {
    if (s_sineTable) {
        std::free(s_sineTable);
        s_sineTable = nullptr;
    }
    s_allocatedSize = 0;
}

} // namespace binaural
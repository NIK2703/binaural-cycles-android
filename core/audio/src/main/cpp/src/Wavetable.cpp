#include "Wavetable.h"

namespace binaural {

// Статические члены
std::vector<float> Wavetable::s_sineTable;
int Wavetable::s_tableSize = DEFAULT_TABLE_SIZE;
int Wavetable::s_tableSizeMask = DEFAULT_TABLE_SIZE - 1;
float Wavetable::s_scaleFactor = DEFAULT_TABLE_SIZE / TWO_PI;
float Wavetable::s_scaleFactorFloat = static_cast<float>(DEFAULT_TABLE_SIZE / TWO_PI);

void Wavetable::initialize(int size) {
    s_tableSize = size;
    s_tableSizeMask = size - 1;
    s_scaleFactor = static_cast<float>(size) / TWO_PI;
    s_scaleFactorFloat = static_cast<float>(s_scaleFactor);
    
    s_sineTable.resize(size);
    for (int i = 0; i < size; ++i) {
        s_sineTable[i] = static_cast<float>(std::sin(TWO_PI * i / size));
    }
}

} // namespace binaural

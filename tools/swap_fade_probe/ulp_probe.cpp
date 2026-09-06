// Проверка гипотезы: если nextafter() отрабатывает в double, а результат
// сужается во float, то T*±1 ULP(double) снова округляются в ТОТ ЖЕ float T*,
// проверка «знак перевернулся» даёт ЛОЖЬ и процедура объявляется невалидной.
#include <cstdio>
#include <cmath>
#include <limits>
#include "Config.h"
#include "ChannelLayout.h"
using namespace binaural;

int main() {
    BinauralConfig cfg;
    cfg.channelSwapEnabled = true;
    cfg.channelSwapMode = ChannelSwapMode::TIMER;
    cfg.channelSwapIntervalSec = 30;

    const float ts[] = {41430.0f, 85800.0f, 30.0f, 43200.0f};
    for (float T : ts) {
        // Вариант А (как в исходнике): float-аргументы
        float bf = std::nextafter(T, -std::numeric_limits<float>::infinity());
        float af = std::nextafter(T,  std::numeric_limits<float>::infinity());

        // Вариант Б: соседние DOUBLE, суженные обратно во float
        double Td = static_cast<double>(T);
        float bd = static_cast<float>(std::nextafter(Td, -std::numeric_limits<double>::infinity()));
        float ad = static_cast<float>(std::nextafter(Td,  std::numeric_limits<double>::infinity()));

        printf("T*=%.4f\n", (double)T);
        printf("  A float  : before=%.6f after=%.6f  state %d/%d -> %s\n",
               (double)bf, (double)af,
               channelSwapStateAt(cfg, bf)?1:0, channelSwapStateAt(cfg, af)?1:0,
               channelSwapStateAt(cfg,bf)==channelSwapStateAt(cfg,af) ? "НЕТ ПЕРЕВОРОТА" : "переворот");
        printf("  B double : before=%.6f after=%.6f  state %d/%d -> %s  %s\n",
               (double)bd, (double)ad,
               channelSwapStateAt(cfg, bd)?1:0, channelSwapStateAt(cfg, ad)?1:0,
               channelSwapStateAt(cfg,bd)==channelSwapStateAt(cfg,ad) ? "НЕТ ПЕРЕВОРОТА" : "переворот",
               (bd == T && ad == T) ? "(ОБА СНОВА РАВНЫ T*)" : "");
    }
    return 0;
}

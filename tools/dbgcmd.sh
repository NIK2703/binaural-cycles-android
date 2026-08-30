#!/bin/bash
# Отправка команды debug-приёмнику и печать ответа.
#
# Использование:  bash tools/dbgcmd.sh status
#                 bash tools/dbgcmd.sh switch 20 250
#
# `am broadcast` печатает resultData построчно, поэтому многострочный ответ
# (status, presets, help) читается прямо из вызова. Если приёмник молчит —
# смотрите logcat: adb logcat -d -t 40 -s BinauralDebug:I '*:S'
#
# Требование: MainActivity должна быть на экране (ViewModel жив). Экран
# заблокирован — команда вернёт "ViewModel не подключён".
set -u
ADB=/home/nikita/tools/android-sdk/platform-tools/adb
PKG=com.binauralcycles.debug
ACTION=com.binauralcycles.debug.COMMAND
CMD="$*"

# Внутренние кавычки обязательны: `adb shell` склеивает аргументы через пробел,
# и команда из нескольких слов на устройстве развалилась бы на части.
"$ADB" shell am broadcast -a "$ACTION" -p "$PKG" --es cmd "'$CMD'" 2>&1 |
    sed -n '/Broadcast completed/,$p'

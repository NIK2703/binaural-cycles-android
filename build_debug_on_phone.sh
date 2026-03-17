#!/bin/bash
#
# Скрипт отладки приложения BinauralBeats на телефоне
# Собирает, устанавливает и сохраняет логи в файл
#
# Использование:
#   ./build_debug_on_phone.sh <IP:PORT> [build_type]
#
# Аргументы:
#   IP:PORT     - адрес устройства для ADB подключения (обязательно)
#   build_type  - тип сборки: "debug" (по умолчанию) или "release"
#
# Примеры:
#   ./build_debug_on_phone.sh 192.168.151.102:5555
#   ./build_debug_on_phone.sh 192.168.151.102:5555 release
#

set -e

# === КОНФИГУРАЦИЯ ===
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REMOTE_CODE_DIR="$(dirname "$SCRIPT_DIR")/RemoteCode3"
APP_PACKAGE_BASE="com.binauralcycles"
APP_ACTIVITY="com.binauralcycles.MainActivity"
APK_PATH="./app/build/outputs/apk/debug/app-debug.apk"
LOG_DIR="./logs"
TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
LOG_FILE="${LOG_DIR}/debug_${TIMESTAMP}.log"

# === ПРОВЕРКА АРГУМЕНТОВ ===
if [ -z "$1" ]; then
    echo "❌ Ошибка: не указан адрес устройства"
    echo ""
    echo "Использование: $0 <IP:PORT> [build_type]"
    echo ""
    echo "Примеры:"
    echo "  $0 192.168.151.102:5555"
    echo "  $0 192.168.151.102:5555 release"
    exit 1
fi

DEVICE_ADDRESS="$1"
BUILD_TYPE="${2:-debug}"

# Определяем package name в зависимости от типа сборки
if [ "$BUILD_TYPE" = "release" ]; then
    APP_PACKAGE="$APP_PACKAGE_BASE"
    APK_DIR="./app/build/outputs/apk/release"
else
    APP_PACKAGE="${APP_PACKAGE_BASE}.debug"
    APK_DIR="./app/build/outputs/apk/debug"
fi

# Функция для поиска APK файла (учитывает split APK по архитектурам)
find_apk() {
    local apk_dir="$1"
    local build_type="$2"
    
    # Сначала пробуем универсальное имя
    if [ -f "$apk_dir/app-${build_type}.apk" ]; then
        echo "$apk_dir/app-${build_type}.apk"
        return
    fi
    
    # Ищем split APK для архитектуры устройства или первый доступный
    local found_apk=$(find "$apk_dir" -name "app-*-${build_type}.apk" -type f 2>/dev/null | head -1)
    if [ -n "$found_apk" ]; then
        echo "$found_apk"
        return
    fi
    
    # Если ничего не нашли, пробуем любой APK в директории
    found_apk=$(find "$apk_dir" -name "*.apk" -type f 2>/dev/null | head -1)
    if [ -n "$found_apk" ]; then
        echo "$found_apk"
        return
    fi
}

# Проверяем наличие Gradle wrapper в RemoteCode3
if [ ! -f "$REMOTE_CODE_DIR/gradlew" ]; then
    echo "❌ Ошибка: Gradle wrapper не найден в $REMOTE_CODE_DIR"
    exit 1
fi

GRADLEW="$REMOTE_CODE_DIR/gradlew"

# Создаём директорию для логов
mkdir -p "$LOG_DIR"

echo "=============================================="
echo "🔧 BinauralBeats Debug Script"
echo "=============================================="
echo "📱 Устройство: $DEVICE_ADDRESS"
echo "📦 Пакет: $APP_PACKAGE"
echo "🏗️  Сборка: $BUILD_TYPE"
echo "🎯 Activity: $APP_ACTIVITY"
echo "📝 Лог-файл: $LOG_FILE"
echo "📂 Директория проекта: $SCRIPT_DIR"
echo "=============================================="
echo ""

# === ФУНКЦИИ ===

# Очистка при выходе
cleanup() {
    local exit_code=$?
    echo ""
    echo "🧹 Остановка сбора логов..."
    
    # Убиваем процесс logcat если работает
    if [ ! -z "$LOGCAT_PID" ]; then
        kill $LOGCAT_PID 2>/dev/null || true
        wait $LOGCAT_PID 2>/dev/null || true
    fi
    
    # Отключаем trap чтобы избежать рекурсии
    trap - EXIT INT TERM
    
    echo ""
    echo "✅ Логи сохранены в: $LOG_FILE"
    
    exit $exit_code
}

# Проверка подключения к устройству
check_device() {
    echo "🔌 Подключение к устройству $DEVICE_ADDRESS..."
    
    # Пробуем подключиться
    adb connect "$DEVICE_ADDRESS" 2>/dev/null
    
    # Проверяем что устройство доступно
    if ! adb -s "$DEVICE_ADDRESS" get-state &>/dev/null; then
        echo "❌ Ошибка: не удалось подключиться к устройству $DEVICE_ADDRESS"
        echo "   Проверьте что устройство доступно по сети и включена отладка по USB"
        exit 1
    fi
    
    echo "✅ Устройство подключено"
}

# Сборка приложения
build_app() {
    echo "🔨 Сборка приложения ($BUILD_TYPE)..."
    
    cd "$SCRIPT_DIR"
    
    local build_output
    local exit_code
    
    if [ "$BUILD_TYPE" = "release" ]; then
        build_output=$("$GRADLEW" :app:assembleRelease --no-daemon 2>&1) || exit_code=$?
    else
        build_output=$("$GRADLEW" :app:assembleDebug --no-daemon 2>&1) || exit_code=$?
    fi
    
    # Выводим последние строки вывода сборки
    echo "$build_output" | tail -5
    
    # Проверяем код возврата
    if [ ! -z "$exit_code" ] && [ "$exit_code" -ne 0 ]; then
        echo ""
        echo "❌ Ошибка: сборка упала с кодом $exit_code"
        echo ""
        echo "📋 Полный вывод сборки:"
        echo "$build_output"
        exit 1
    fi
    
    # Ищем APK файл (учитывает split APK по архитектурам)
    APK_PATH=$(find_apk "$APK_DIR" "$BUILD_TYPE")
    
    if [ -z "$APK_PATH" ] || [ ! -f "$APK_PATH" ]; then
        echo "❌ Ошибка: APK не найден в директории $APK_DIR"
        echo "   Доступные файлы:"
        ls -la "$APK_DIR" 2>/dev/null || echo "   Директория не существует"
        exit 1
    fi
    
    echo "📦 Найден APK: $APK_PATH"
    echo "✅ Сборка завершена"
}

# Установка приложения
install_app() {
    echo "📦 Установка приложения..."
    
    # Устанавливаем поверх с сохранением данных (-r = reinstall, сохраняет данные)
    if ! adb -s "$DEVICE_ADDRESS" install -r "$APK_PATH"; then
        echo "❌ Ошибка: не удалось установить приложение"
        exit 1
    fi
    
    echo "✅ Приложение установлено (данные сохранены)"
}

# Получение PID приложения
get_app_pid() {
    local pid=""
    
    # Метод: pidof (самый быстрый)
    pid=$(adb -s "$DEVICE_ADDRESS" shell "pidof $APP_PACKAGE 2>/dev/null" | tr -d '\r\n' | awk '{print $1}')
    
    echo "$pid"
}

# Запуск приложения
launch_app() {
    echo "🚀 Запуск приложения..."
    
    # Сначала проверяем, не запущено ли уже приложение
    local existing_pid=$(get_app_pid)
    if [ ! -z "$existing_pid" ]; then
        echo "⚠️  Приложение уже запущено (PID: $existing_pid). Завершаем..."
        adb -s "$DEVICE_ADDRESS" shell "am force-stop $APP_PACKAGE"
        sleep 1
    fi
    
    # Запускаем приложение
    adb -s "$DEVICE_ADDRESS" shell "am start -n $APP_PACKAGE/$APP_ACTIVITY" 2>/dev/null
    
    if [ $? -ne 0 ]; then
        echo "❌ Ошибка: не удалось запустить приложение"
        echo "   Проверьте что приложение установлено на устройстве"
        exit 1
    fi
    
    echo "✅ Команда запуска отправлена"
}

# === ОСНОВНОЙ КОД ===

# Устанавливаем обработчики сигналов
trap cleanup EXIT INT TERM

# Шаг 1: Подключение к устройству
check_device

# Шаг 2: Сборка приложения
build_app

# Шаг 3: Установка приложения
install_app

# Шаг 4: Запуск приложения
launch_app

# Шаг 5: Ждём запуска и получаем PID
echo "⏳ Ожидание запуска приложения..."
sleep 2
APP_PID=$(get_app_pid)

if [ -z "$APP_PID" ]; then
    echo "❌ Не удалось получить PID приложения"
    exit 1
fi

echo "✅ Приложение запущено (PID: $APP_PID)"

# Очищаем предыдущие логи
echo "📝 Очистка буфера логов..."
adb -s "$DEVICE_ADDRESS" logcat -c

# Записываем заголовок в лог-файл
{
    echo "=============================================="
    echo "BinauralBeats Debug Log"
    echo "=============================================="
    echo "Дата: $(date)"
    echo "Устройство: $DEVICE_ADDRESS"
    echo "Пакет: $APP_PACKAGE"
    echo "PID: $APP_PID"
    echo "Сборка: $BUILD_TYPE"
    echo "=============================================="
    echo ""
} > "$LOG_FILE"

echo ""
echo "=============================================="
echo "📊 СБОР ЛОГОВ (PID: $APP_PID)"
echo "=============================================="
echo "📝 Логи пишутся в: $LOG_FILE"
echo "💡 Нажмите Ctrl+C для завершения"
echo ""

# Запускаем logcat в фоновом режиме
adb -s "$DEVICE_ADDRESS" logcat -v time --pid="$APP_PID" 2>/dev/null >> "$LOG_FILE" &
LOGCAT_PID=$!

# Простой цикл отслеживания - проверяем только существование процесса
while true; do
    sleep 1
    
    # Проверяем жив ли процесс logcat
    if ! kill -0 $LOGCAT_PID 2>/dev/null; then
        echo ""
        echo "🛑 Приложение закрыто"
        break
    fi
    
    # Проверяем PID приложения
    CURRENT_PID=$(get_app_pid)
    if [ -z "$CURRENT_PID" ]; then
        echo ""
        echo "🛑 Приложение закрыто (PID $APP_PID больше не существует)"
        break
    fi
done

echo ""
echo "✅ Отладка завершена"
echo "📝 Логи сохранены в: $LOG_FILE"

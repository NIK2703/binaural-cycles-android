#pragma once

// Заглушка для android/log.h при тестировании на хост-машине

#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO  4
#define ANDROID_LOG_WARN  5
#define ANDROID_LOG_ERROR 6

#include <cstdio>
#include <cstdarg>

inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    const char* level;
    switch (prio) {
        case ANDROID_LOG_DEBUG: level = "D"; break;
        case ANDROID_LOG_INFO:  level = "I"; break;
        case ANDROID_LOG_WARN:  level = "W"; break;
        case ANDROID_LOG_ERROR: level = "E"; break;
        default: level = "?";
    }
    
    printf("[%s/%s] ", level, tag);
    
    va_list args;
    va_start(args, fmt);
    vprintf(fmt, args);
    va_end(args);
    
    printf("\n");
    return 0;
}

// Заглушка для localtime_r
#include <ctime>
inline struct tm* localtime_r(const time_t* timep, struct tm* result) {
#ifdef _WIN32
    localtime_s(result, timep);
    return result;
#else
    return localtime_r(timep, result);
#endif
}

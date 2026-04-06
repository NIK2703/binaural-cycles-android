package com.binauralcycles.viewmodel.state

/**
 * Generic sealed interface для моделирования состояний UI.
 * Используется для явного разделения Loading, Success и Error состояний.
 */
sealed interface UiState<out T> {
    /**
     * Состояние загрузки данных
     */
    data object Loading : UiState<Nothing>
    
    /**
     * Состояние успешной загрузки с данными
     */
    data class Success<T>(val data: T) : UiState<T>
    
    /**
     * Состояние ошибки с сообщением
     */
    data class Error(val message: String) : UiState<Nothing>
    
    /**
     * Проверка, находится ли в состоянии загрузки
     */
    val isLoading: Boolean get() = this is Loading
    
    /**
     * Проверка, находится ли в состоянии ошибки
     */
    val isError: Boolean get() = this is Error
    
    /**
     * Проверка, находится ли в состоянии успеха
     */
    val isSuccess: Boolean get() = this is Success
    
    /**
     * Получить данные, если в состоянии успеха
     */
    fun getOrNull(): T? = (this as? Success)?.data
    
    /**
     * Получить сообщение об ошибке, если в состоянии ошибки
     */
    fun getErrorMessage(): String? = (this as? Error)?.message
    
    /**
     * Преобразовать данные, если в состоянии успеха
     */
    fun <R> map(transform: (T) -> R): UiState<R> = when (this) {
        is Loading -> Loading
        is Success -> Success(transform(data))
        is Error -> Error(message)
    }
    
    companion object {
        /**
         * Создать состояние успеха
         */
        fun <T> success(data: T): UiState<T> = Success(data)
        
        /**
         * Создать состояние ошибки
         */
        fun error(message: String): UiState<Nothing> = Error(message)
        
        /**
         * Создать состояние загрузки
         */
        fun loading(): UiState<Nothing> = Loading
    }
}
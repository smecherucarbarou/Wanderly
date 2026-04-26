package com.novahorizon.wanderly.api

import java.io.IOException

sealed class NetworkResult<out T> {
    data class Success<T>(val data: T) : NetworkResult<T>()
    data class HttpError(val code: Int, val message: String) : NetworkResult<Nothing>()
    data class NetworkError(val cause: IOException) : NetworkResult<Nothing>()
    data class ParseError(val cause: Exception) : NetworkResult<Nothing>()
    object Timeout : NetworkResult<Nothing>()
}

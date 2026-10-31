package com.jobscout.feature.search.data

fun interface FreehireTokenProvider { suspend fun token(): String? }
class MissingFreehireKeyException : IllegalStateException()

object FreehireKeyInput {
    fun normalize(value: String): String {
        val token = value.trim()
        require(token.length in 1..2048 && token.all { it.code in 33..126 }) {
            "Informe somente a chave, sem espaços, prefixo Bearer ou quebras de linha."
        }
        return token
    }
}

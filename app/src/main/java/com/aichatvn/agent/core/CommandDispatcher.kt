package com.aichatvn.agent.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommandDispatcher @Inject constructor() {
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val _commands = MutableSharedFlow<suspend () -> Unit>()
    val commands = _commands.asSharedFlow()
    
    private val _results = MutableSharedFlow<Result<Any>>()
    val results = _results.asSharedFlow()
    
    init {
        scope.launch {
            _commands.collect { command ->
                try {
                    command()
                } catch (e: Exception) {
                    _results.emit(Result.failure(e))
                }
            }
        }
    }
    
    suspend fun dispatch(
        block: suspend () -> Any,
        dispatcher: CoroutineDispatcher = Dispatchers.IO
    ): Result<Any> {
        return try {
            val result = kotlinx.coroutines.withContext(dispatcher) {
                block()
            }
            _results.emit(Result.success(result))
            Result.success(result)
        } catch (e: Exception) {
            _results.emit(Result.failure(e))
            Result.failure(e)
        }
    }
    
    fun dispatchAsync(block: suspend () -> Unit) {
        scope.launch {
            block()
        }
    }
}
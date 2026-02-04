package com.example.signex.utils

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

/**
 * Thread-safe file logger that writes logs to external storage.
 * Logs are saved to app-specific external storage (no permission required)
 */
class FileLogger private constructor(private val appContext: Context) {
    
    companion object {
        private const val TAG = "FileLogger"
        private const val LOG_DIR = "SignexLogs"
        private const val MAX_LOG_SIZE = 5 * 1024 * 1024 // 5MB
        
        @Volatile
        private var instance: FileLogger? = null
        
        fun getInstance(context: Context): FileLogger {
            return instance ?: synchronized(this) {
                instance ?: FileLogger(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val logQueue = LinkedBlockingQueue<LogEntry>()
    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileNameFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    
    private var currentLogFile: File? = null
    private var fileWriter: FileWriter? = null
    
    data class LogEntry(
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null
    )
    
    init {
        // Initialize log file
        try {
            val logDir = getLogDirectory()
            if (logDir != null) {
                val fileName = "signex_log_${fileNameFormat.format(Date())}.txt"
                currentLogFile = File(logDir, fileName)
                fileWriter = FileWriter(currentLogFile, true)
                
                // Write header
                fileWriter?.apply {
                    write("=" * 80 + "\n")
                    write("SignEx Application Log\n")
                    write("Started: ${dateFormat.format(Date())}\n")
                    write("=" * 80 + "\n\n")
                    flush()
                }
                
                Log.i(TAG, "Log file created: ${currentLogFile?.absolutePath}")
            } else {
                Log.e(TAG, "Failed to create log directory")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize logger", e)
        }
        
        // Start background writer thread
        executor.execute {
            while (true) {
                try {
                    val entry = logQueue.take()
                    writeToFile(entry)
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error writing log", e)
                }
            }
        }
    }
    
    private fun getLogDirectory(): File? {
        return try {
            // On Android 10+ (API 29+), use app-specific external storage
            // This doesn't require WRITE_EXTERNAL_STORAGE permission
            val appExternalDir = appContext.getExternalFilesDir(null)
            
            if (appExternalDir != null) {
                val logDir = File(appExternalDir, LOG_DIR)
                
                if (!logDir.exists()) {
                    logDir.mkdirs()
                }
                
                if (logDir.exists() && logDir.canWrite()) {
                    Log.i(TAG, "Using app-specific storage: ${logDir.absolutePath}")
                    return logDir
                }
            }
            
            // Fallback: Try Documents directory (requires permission on Android 10+)
            // This will only work if user manually grants permission in settings
            try {
                val documentsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
                val logDir = File(documentsDir, LOG_DIR)
                
                if (!logDir.exists()) {
                    logDir.mkdirs()
                }
                
                if (logDir.exists() && logDir.canWrite()) {
                    Log.i(TAG, "Using Documents folder: ${logDir.absolutePath}")
                    return logDir
                }
            } catch (e: Exception) {
                Log.w(TAG, "Documents folder not accessible, using app-specific storage")
            }
            
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error creating log directory", e)
            null
        }
    }
    
    private fun writeToFile(entry: LogEntry) {
        try {
            // Check file size and rotate if needed
            currentLogFile?.let { file ->
                if (file.length() > MAX_LOG_SIZE) {
                    rotateLogFile()
                }
            }
            
            fileWriter?.apply {
                val timestamp = dateFormat.format(Date(entry.timestamp))
                val logLine = "[$timestamp] [${entry.level}] [${entry.tag}] ${entry.message}\n"
                write(logLine)
                
                // Write exception stack trace if present
                entry.throwable?.let { throwable ->
                    write("Exception: ${throwable.javaClass.simpleName}: ${throwable.message}\n")
                    throwable.stackTrace.take(10).forEach { element ->
                        write("  at $element\n")
                    }
                }
                
                flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log entry", e)
        }
    }
    
    private fun rotateLogFile() {
        try {
            fileWriter?.close()
            
            val logDir = getLogDirectory()
            if (logDir != null) {
                val fileName = "signex_log_${fileNameFormat.format(Date())}.txt"
                currentLogFile = File(logDir, fileName)
                fileWriter = FileWriter(currentLogFile, true)
                
                // Clean old logs (keep last 10)
                logDir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(10)?.forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rotate log file", e)
        }
    }
    
    fun d(tag: String, message: String) {
        Log.d(tag, message)
        logQueue.offer(LogEntry(System.currentTimeMillis(), "DEBUG", tag, message))
    }
    
    fun i(tag: String, message: String) {
        Log.i(tag, message)
        logQueue.offer(LogEntry(System.currentTimeMillis(), "INFO", tag, message))
    }
    
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        logQueue.offer(LogEntry(System.currentTimeMillis(), "WARN", tag, message, throwable))
    }
    
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        logQueue.offer(LogEntry(System.currentTimeMillis(), "ERROR", tag, message, throwable))
    }
    
    fun getLogFilePath(): String? {
        return currentLogFile?.absolutePath
    }
    
    fun close() {
        try {
            executor.shutdown()
            fileWriter?.apply {
                write("\n" + "=" * 80 + "\n")
                write("Log closed: ${dateFormat.format(Date())}\n")
                write("=" * 80 + "\n")
                flush()
                close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error closing logger", e)
        }
    }
}

private operator fun String.times(n: Int): String = this.repeat(n)

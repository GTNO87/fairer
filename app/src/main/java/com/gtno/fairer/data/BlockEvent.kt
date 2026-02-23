package com.gtno.fairer.data

data class BlockEvent(
    val domain: String,
    val category: String,
    val appName: String,
    val packageName: String,
    val timestamp: Long = System.currentTimeMillis(),
)

package com.folderbackup.agent.network

data class N8nTestResult(
    val ok: Boolean,
    val n8nConfigured: Boolean,
    val n8nOk: Boolean,
    val n8nStatus: Int?,
    val error: String?,
)

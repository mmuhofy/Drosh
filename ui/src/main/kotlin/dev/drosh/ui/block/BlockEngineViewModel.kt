package dev.drosh.ui.block

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.drosh.domain.block.Block
import dev.drosh.domain.block.BlockEngineState
import dev.drosh.domain.block.BlockRepository
import dev.drosh.domain.block.NetworkMetricsCollector
import dev.drosh.domain.terminal.SubmitBlockCommandUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Block Mode terminal view.
 *
 * Exposes the block list and currently-running block to the UI, and
 * drives a 500ms network-refresh loop that pushes live RX/TX totals
 * into the running block via [BlockRepository.updateRunningCounters].
 *
 * User actions (run, cancel, toggle collapse) are forwarded to the
 * repository — this VM is a thin observer.
 *
 * UNTESTED — verify before use.
 */
@HiltViewModel
class BlockEngineViewModel @Inject constructor(
    private val blockRepository: BlockRepository,
    private val trafficStats: NetworkMetricsCollector,
    private val submitCommand: SubmitBlockCommandUseCase,
    private val blockEngineState: BlockEngineState,
) : ViewModel() {

    val blocks: StateFlow<List<Block>> = blockRepository.observe()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val runningBlock: StateFlow<Block?> = blockRepository.observeRunningBlock()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _networkEnabled = MutableStateFlow(false)
    val networkEnabled: StateFlow<Boolean> = _networkEnabled.asStateFlow()

    private val _hiddenIds = MutableStateFlow<Set<String>>(emptySet())
    val hiddenIds: StateFlow<Set<String>> = _hiddenIds.asStateFlow()

    private val _pendingEdit = MutableStateFlow<String?>(null)
    val pendingEdit: StateFlow<String?> = _pendingEdit.asStateFlow()

    private val _exportRequest = MutableStateFlow<String?>(null)
    val exportRequest: StateFlow<String?> = _exportRequest.asStateFlow()

    private val _clipboardRequest = MutableStateFlow<ClipboardEvent?>(null)
    val clipboardRequest: StateFlow<ClipboardEvent?> = _clipboardRequest.asStateFlow()

    /** Last directory inferred from the prompt. */
    val lastDir: StateFlow<String> = MutableStateFlow(blockEngineState.lastDir).also { sf ->
        viewModelScope.launch {
            while (true) {
                sf.value = blockEngineState.lastDir
                delay(500L)
            }
        }
    }.asStateFlow()

    /** Prompt suffix character extracted from the latest prompt (e.g. "$", "#", "❯"). */
    val promptSuffix: StateFlow<String> = MutableStateFlow(
        blockEngineState.lastPrompt.extractSuffix()
    ).also { sf ->
        viewModelScope.launch {
            while (true) {
                sf.value = blockEngineState.lastPrompt.extractSuffix()
                delay(500L)
            }
        }
    }.asStateFlow()

    fun consumePendingEdit() { _pendingEdit.value = null }
    fun consumeExportRequest() { _exportRequest.value = null }
    fun consumeClipboardRequest() { _clipboardRequest.value = null }

    sealed interface ClipboardEvent {
        data class Command(val prompt: String, val command: String) : ClipboardEvent
        data class Output(val text: String) : ClipboardEvent
    }

    private var networkJob: Job? = null

    init {
        startNetworkTicker()
    }

    private fun startNetworkTicker() {
        if (networkJob?.isActive == true) return
        networkJob = viewModelScope.launch {
            _networkEnabled.value = true
            while (true) {
                tickRunningBlock()
                delay(NETWORK_TICK_MS)
            }
        }
    }

    private fun tickRunningBlock() {
        val running = runningBlock.value ?: return
        val (rx, tx) = trafficStats.snapshotTotals()
        blockRepository.updateRunningCounters(
            blockId = running.id,
            currentRxBytes = rx,
            currentTxBytes = tx,
        )
        // Force a re-emit of the running block so the elapsed-time label
        // updates every 500ms — updateRunningCounters is a no-op when
        // network totals do not change, which would otherwise leave the
        // duration frozen on the UI.
        blockRepository.bumpRunningBlock(running.id)
    }

    fun onCommandSubmitted(prompt: String, command: String) {
        val (rx, tx) = trafficStats.snapshotTotals()
        val resolvedPrompt = prompt.takeIf { it.isNotBlank() } ?: blockEngineState.lastPrompt
        if (command.trim() == "clear" || command.trim() == "ctrl+l") {
            blockRepository.clear()
        }
        blockRepository.onCommandSubmitted(
            prompt = resolvedPrompt,
            command = command,
            startRxBytes = rx,
            startTxBytes = tx,
        )
        viewModelScope.launch { submitCommand.submit(command) }
    }

    fun onCommandCompleted(exitCode: Int) {
        blockRepository.onCommandCompleted(exitCode)
    }

    fun onCommandCancelled() {
        blockRepository.onCommandCancelled()
    }

    fun onToggleCollapsed(blockId: String) {
        val current = blocks.value.firstOrNull { it.id == blockId } ?: return
        blockRepository.setCollapsed(blockId, !current.isCollapsed)
    }

    fun onOutputChunk(chunk: String) {
        blockRepository.onOutputChunk(chunk)
    }

    /**
     * Drops all rendered blocks. The user runs `clear` from the terminal
     * — when the shell prompt re-emits with an empty buffer the wire
     * detects a clean state and the screen empties.
     */
    fun onClearBlocks() {
        blockRepository.clear()
    }

    fun onRerunCommand(command: String) {
        onCommandSubmitted("", command)
    }

    fun onEditCommand(command: String) {
        _pendingEdit.value = command
    }

    fun onDeleteBlock(blockId: String) {
        // Repository has no remove; in v1, hide client-side via a
        // hiddenIds set. Future: Room delete.
        _hiddenIds.value = _hiddenIds.value + blockId
    }

    fun onCopyCommand(block: Block) {
        // Emit clipboard event with the formatted text; the screen
        // consumes it (LocalClipboardManager is composable-scoped).
        _clipboardRequest.value = ClipboardEvent.Command(block.prompt, block.command)
    }

    fun onCopyOutput(block: Block) {
        _clipboardRequest.value = ClipboardEvent.Output(block.outputLines.joinToString("\n"))
    }

    fun onExportOutput(block: Block) {
        _exportRequest.value = block.id
    }

    override fun onCleared() {
        super.onCleared()
        networkJob?.cancel()
    }

    private companion object {
        const val NETWORK_TICK_MS = 500L
    }
}

private val PROMPT_SUFFIX_REGEX = Regex("""[#$❯➜]\s*$""")

private fun String.extractSuffix(): String {
    val match = PROMPT_SUFFIX_REGEX.find(this.trim())
    return if (match != null) match.value.trim() else "$"
}

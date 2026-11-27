package dev.drosh.terminal.session

/**
 * Source-compatible name for callers that still inject the old wire.
 *
 * The transcript-diff implementation is gone: every caller of this name now
 * gets [EngineBlockBridge], which drains engine records instead of polling a
 * rendered transcript. The alias stays until the remaining injection sites are
 * renamed; it carries no logic of its own.
 */
@Deprecated(
    "Use EngineBlockBridge — blocks come from engine events, not transcript diffs.",
    ReplaceWith("EngineBlockBridge(blockRepository)"),
)
typealias BlockEventBridge = EngineBlockBridge

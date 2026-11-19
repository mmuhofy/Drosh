package dev.drosh.ui.session

// UNTESTED — verify before use: pure helpers for the session grid's UI-local
// ordering and grouping. They never read Compose state; the screen owns the
// state and calls these on every drag/drop so every result is immutable,
// idempotent and duplicate-free.

/** A group needs at least two live members; below that it dissolves to singles. */
internal const val MIN_GROUP_MEMBERS = 2

/**
 * Keeps a remembered drag order in sync with the sessions that are still live.
 * Unknown ids are dropped and new sessions are appended in the repository's
 * order, so a closed session can never leave a hole in the grid.
 */
internal fun reconcileSessionOrder(order: List<String>, liveIds: List<String>): List<String> {
    val live = liveIds.toSet()
    val kept = order.filter { it in live }
    val seen = kept.toSet()
    return kept + liveIds.filterNot { it in seen }
}

/**
 * Drops closed members from every group. A group that falls below
 * [MIN_GROUP_MEMBERS] disappears here, which returns its members to the singles
 * view — grouping can never strand a session without an ungroup path.
 */
internal fun liveSessionGroups(groups: List<SessionGroup>, liveIds: Set<String>): List<SessionGroup> =
    groups.mapNotNull { group ->
        val members = group.memberIds.filter { it in liveIds }
        if (members.size >= MIN_GROUP_MEMBERS) group.copy(memberIds = members) else null
    }

/**
 * Adds [incoming] to [target] and removes every group that contributed a member.
 * Calling it with a member already in [target] is a no-op apart from rebuilding
 * the list, so dropping the same card twice cannot create a duplicate.
 */
internal fun addToGroup(
    groups: List<SessionGroup>,
    target: SessionGroup,
    incoming: List<String>,
): List<SessionGroup> {
    val affected = (target.memberIds + incoming).toSet()
    val kept = groups.filterNot { group -> group.memberIds.any { it in affected } }
    return kept + SessionGroup((target.memberIds + incoming).distinct())
}

/** Removes one member. The group dissolves when it would fall below two members. */
internal fun removeFromGroup(
    groups: List<SessionGroup>,
    target: SessionGroup,
    memberId: String,
): List<SessionGroup> {
    val remaining = target.memberIds.filterNot { it == memberId }
    // Match by membership, not by set equality: [target] may be a live copy that
    // already dropped a closed member, while [groups] still holds the original.
    val ids = target.memberIds.toSet()
    val kept = groups.filterNot { group -> group.memberIds.any { it in ids } }
    return if (remaining.size >= MIN_GROUP_MEMBERS) kept + SessionGroup(remaining) else kept
}

/** Dissolves a group completely, returning all of its members to the singles view. */
internal fun ungroup(groups: List<SessionGroup>, target: SessionGroup): List<SessionGroup> {
    val ids = target.memberIds.toSet()
    return groups.filterNot { group -> group.memberIds.any { it in ids } }
}

/**
 * Group copies are rebuilt on every composition, so identity is compared by
 * membership rather than by object reference.
 */
internal fun SessionGroup.sameMembersAs(other: SessionGroup): Boolean =
    memberIds.toSet() == other.memberIds.toSet()

/**
 * Moves the [heldSlotId] slot in front of [targetSlotId]. Unknown or identical
 * ids leave the order untouched; the caller's list is never mutated.
 */
internal fun moveSlot(order: List<String>, heldSlotId: String, targetSlotId: String): List<String> {
    if (heldSlotId == targetSlotId) return order
    val without = order.filterNot { it == heldSlotId }
    val index = without.indexOf(targetSlotId)
    if (index < 0) return without + heldSlotId
    return without.toMutableList().also { it.add(index, heldSlotId) }.toList()
}

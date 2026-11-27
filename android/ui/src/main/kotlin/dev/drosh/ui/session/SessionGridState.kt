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
    // The target's name survives an addition: a group the user named is still
    // that group after one more card joins it.
    return kept + SessionGroup((target.memberIds + incoming).distinct(), target.name)
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
    return if (remaining.size >= MIN_GROUP_MEMBERS) {
        kept + SessionGroup(remaining, target.name)
    } else {
        kept
    }
}

/** Dissolves a group completely, returning all of its members to the singles view. */
internal fun ungroup(groups: List<SessionGroup>, target: SessionGroup): List<SessionGroup> {
    val ids = target.memberIds.toSet()
    return groups.filterNot { group -> group.memberIds.any { it in ids } }
}

/**
 * Turns two ungrouped cards into one UI group, [targetId] first.
 *
 * Target-first because a group card takes the slot of its first member: the
 * new group appears where the card that was dropped *on* already stood, and
 * the held card moves into it rather than dragging the pair to the held
 * card's old position.
 *
 * Both ids must be ungrouped — the caller resolves the target and held groups
 * first and only reaches this for two singles — so nothing is dissolved here
 * and no member can be stranded.
 *
 * UNTESTED — verify before use.
 */
internal fun groupTwoSessions(
    groups: List<SessionGroup>,
    targetId: String,
    heldId: String,
): List<SessionGroup> {
    if (targetId == heldId) return groups
    return groups + SessionGroup(listOf(targetId, heldId))
}

/**
 * Moves one member [offset] places inside its group, clamped to the ends.
 *
 * The panel reorders with arrows rather than a drag: a member row is 56dp and a
 * drag inside a scrolling list is a gesture the scroll claims first.
 */
internal fun moveGroupMember(group: SessionGroup, memberId: String, offset: Int): SessionGroup {
    val from = group.memberIds.indexOf(memberId)
    if (from < 0 || offset == 0) return group
    val to = (from + offset).coerceIn(0, group.memberIds.lastIndex)
    if (to == from) return group
    val reordered = group.memberIds.toMutableList()
    reordered.removeAt(from)
    reordered.add(to, memberId)
    return group.copy(memberIds = reordered)
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

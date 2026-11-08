package org.snrkl.indexrouter.routing

/**
 * Which group a rule belongs to: the common group, or one of the two ring
 * gestures.
 *
 * [wireName] is a stable internal id. It is what the database, the webhook's
 * `X-Index-Trigger` header and the config file hold, so it is never renamed and
 * never shown. The name a person reads is separate and editable — see
 * [defaultName] and SettingsStore.groupName.
 *
 * A trigger group is bound to exactly one vault. The common group belongs to
 * no vault: its rules are the ones that need none, and they are evaluated
 * whichever gesture fired.
 *
 * No Android types: covered by JVM unit tests.
 */
enum class RuleGroup(val wireName: String, val defaultName: String) {
    COMMON("common", "common"),
    SINGLE_CLICK_HOLD("single-click-hold", "click-hold"),
    DOUBLE_CLICK_HOLD("double-click-hold", "doubleclick-hold"),

    /**
     * The vault an install had before there were two, with its file and task
     * rules, waiting to be given to a gesture. Nothing is inferred about which
     * gesture it belongs to: the user assigns it. Until then its rules are
     * evaluated for neither gesture.
     */
    CARRIED_OVER("carried-over", "existing vault"),
    ;

    val isTrigger: Boolean get() = this == SINGLE_CLICK_HOLD || this == DOUBLE_CLICK_HOLD

    /** Whether this group has a vault of its own: a gesture, or the carried-over vault. */
    val hasVault: Boolean get() = this != COMMON

    /** The rule types this group may hold. */
    fun allows(action: ActionType): Boolean =
        if (hasVault) action in VAULT_ACTIONS else action !in VAULT_ACTIONS

    /** The other gesture, for cross-vault re-route. Null for the common group. */
    val other: RuleGroup?
        get() = when (this) {
            COMMON, CARRIED_OVER -> null
            SINGLE_CLICK_HOLD -> DOUBLE_CLICK_HOLD
            DOUBLE_CLICK_HOLD -> SINGLE_CLICK_HOLD
        }

    companion object {
        /** The two gestures, in the order they are drawn. */
        val TRIGGERS: List<RuleGroup> = listOf(SINGLE_CLICK_HOLD, DOUBLE_CLICK_HOLD)

        /** Types that write into a vault, and so belong to a trigger group. */
        private val VAULT_ACTIONS = setOf(ActionType.FILE, ActionType.TASK)

        fun fromWire(value: String?): RuleGroup? =
            entries.firstOrNull { it.wireName.equals(value?.trim(), ignoreCase = true) }

        /** The common group for types that need no vault; [trigger] for the rest. */
        fun forAction(action: ActionType, trigger: RuleGroup): RuleGroup =
            if (COMMON.allows(action)) COMMON else trigger
    }
}

/**
 * What the `X-Index-Trigger` header says, interpreted.
 *
 * Pebble sets the header itself and a user cannot override it, so anything
 * other than the two gestures or the test event is a delivery this app does
 * not understand. It is rejected rather than guessed at: guessing a vault is
 * how a note ends up in the wrong vault.
 */
sealed interface TriggerHeader {
    data class Gesture(val group: RuleGroup) : TriggerHeader
    /** Pebble's "Send test event". Answered, logged, and otherwise ignored. */
    data object TestEvent : TriggerHeader
    data object Missing : TriggerHeader
    data class Unknown(val value: String) : TriggerHeader

    companion object {
        const val TEST_EVENT = "test-event"

        fun parse(raw: String?): TriggerHeader {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty()) return Missing
            if (value.equals(TEST_EVENT, ignoreCase = true)) return TestEvent
            val group = RuleGroup.fromWire(value)
            return if (group != null && group.isTrigger) Gesture(group) else Unknown(value.take(64))
        }
    }
}

package org.snrkl.indexrouter.routing

/**
 * How the two gestures map onto vaults.
 *
 * **One vault** is the default: both gestures file into the same vault, there
 * is one rule list, and a rule can be limited to one gesture. The vault's
 * settings are held in [home]'s profile, so a later switch to a vault per
 * gesture keeps it where it is.
 *
 * **A vault per gesture** is the opt-in: each gesture has a vault of its own,
 * and the file and task rules are grouped by the vault they write into.
 *
 * The matcher is the same in both: a gesture sees the common rules and the
 * rules of the vault it files into, minus any limited to the other gesture.
 * Only which vault that is changes between modes.
 *
 * No Android types: covered by JVM unit tests.
 */
data class VaultMode(
    val perGesture: Boolean = false,
    /** In one-vault mode, the gesture whose profile holds the vault. */
    val home: RuleGroup = RuleGroup.SINGLE_CLICK_HOLD,
) {
    init {
        require(home.isTrigger) { "The vault is held by a gesture" }
    }

    /** The group whose vault [gesture] files into. */
    fun vaultFor(gesture: RuleGroup): RuleGroup = if (perGesture) gesture else home

    /** The groups that have a vault in use, in the order they are drawn. */
    val vaultGroups: List<RuleGroup>
        get() = if (perGesture) RuleGroup.TRIGGERS else listOf(home)

    /** Whether a recording from [gesture] is checked against [rule]. */
    fun sees(gesture: RuleGroup, rule: RoutingRule): Boolean {
        if (rule.gesture != null && rule.gesture != gesture) return false
        return rule.group == RuleGroup.COMMON || rule.group == vaultFor(gesture)
    }

    /**
     * Whether two rules could ever be checked against the same recording, and
     * so must not share a keyword. In one vault, "idea" limited to click-hold
     * and "idea" limited to doubleclick-hold never meet.
     */
    fun overlap(a: RoutingRule, b: RoutingRule): Boolean =
        RuleGroup.TRIGGERS.any { sees(it, a) && sees(it, b) }

    /** Whether [rule] is in use at all in this mode. A rule in a vault not in use is not. */
    fun inUse(rule: RoutingRule): Boolean = RuleGroup.TRIGGERS.any { sees(it, rule) }

    /** Where a rule of [action] belongs when it is added for [gesture]'s vault. */
    fun groupFor(action: ActionType, gesture: RuleGroup = home): RuleGroup =
        RuleGroup.forAction(action, vaultFor(gesture))
}
